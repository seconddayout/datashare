package org.icij.datashare.text.indexing.elasticsearch;

import com.google.inject.Inject;
import org.elasticsearch.action.bulk.BulkItemResponse;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.get.GetRequest;
import org.elasticsearch.action.get.GetResponse;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.support.WriteRequest;
import org.elasticsearch.action.update.UpdateRequest;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.ConstantScoreQueryBuilder;
import org.elasticsearch.index.query.TermsQueryBuilder;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.script.Script;
import org.elasticsearch.script.ScriptType;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.icij.datashare.Entity;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.json.JsonObjectMapper;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.NamedEntity;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.text.nlp.Pipeline;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static java.util.Arrays.asList;
import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.toList;
import static org.elasticsearch.common.xcontent.XContentFactory.jsonBuilder;
import static org.elasticsearch.index.query.QueryBuilders.boolQuery;
import static org.elasticsearch.index.query.QueryBuilders.matchQuery;
import static org.icij.datashare.text.indexing.elasticsearch.ElasticsearchConfiguration.DEFAULT_SEARCH_SIZE;


public class ElasticsearchIndexer implements Indexer {
    private final RestHighLevelClient client;
    private final ElasticsearchConfiguration esCfg;

    @Inject
    public ElasticsearchIndexer(final RestHighLevelClient esClient, final PropertiesProvider propertiesProvider) {
        this.client = esClient;
        esCfg = new ElasticsearchConfiguration(propertiesProvider);
        LOGGER.info("indexer defined with {}", esCfg);
    }

    @Override
    public void close() throws IOException {
        LOGGER.info("Closing Elasticsearch connections");
        client.close();
        LOGGER.info("Elasticsearch connections closed");
    }

    @Override
    public boolean bulkAdd(final String indexName, Pipeline.Type nerType, List<NamedEntity> namedEntities, Document parent) throws IOException {
        BulkRequest bulkRequest = new BulkRequest();

        String routing = ofNullable(parent.getRootDocument()).orElse(parent.getId());
        bulkRequest.add(new UpdateRequest(indexName, esCfg.indexType, parent.getId()).doc(
                jsonBuilder().startObject()
                        .field("status", Document.Status.DONE)
                        .endObject()).routing(routing));
        bulkRequest.add(new UpdateRequest(indexName, esCfg.indexType, parent.getId())
                .script(new Script(ScriptType.INLINE, "painless",
                        "if (!ctx._source.nerTags.contains(params.nerTag)) ctx._source.nerTags.add(params.nerTag);",
                        new HashMap<String, Object>() {{put("nerTag", nerType.toString());}})).routing(routing));

        for (Entity child : namedEntities) {
            bulkRequest.add(createIndexRequest(indexName, JsonObjectMapper.getType(child), child.getId(),
                            JsonObjectMapper.getJson(child), parent.getId(), routing));
        }
        bulkRequest.setRefreshPolicy(esCfg.refreshPolicy);

        BulkResponse bulkResponse = client.bulk(bulkRequest);
        if (bulkResponse.hasFailures()) {
            for (BulkItemResponse resp : bulkResponse.getItems()) {
                if (resp.isFailed()) {
                    LOGGER.error("bulk add failed : {}", resp.getFailureMessage());
                }
            }
            return false;
        }
        return true;
    }

    @Override
    public <T extends Entity> boolean add(final String indexName, T obj) {
        String type = JsonObjectMapper.getType(obj);
        String id = obj.getId();
        try {
            final IndexResponse resp = client.index( createIndexRequest(indexName, type, id,
                    JsonObjectMapper.getJson(obj),
                    JsonObjectMapper.getParent(obj),
                    JsonObjectMapper.getRoot(obj)).setRefreshPolicy(esCfg.refreshPolicy) );
            return asList(RestStatus.CREATED, RestStatus.OK).contains(resp.status());
        } catch (IOException ioex) {
            LOGGER.error("Failed to add doc " + id + " of type " + type + " in index " + indexName, ioex);
            return false;
        }
    }

    private IndexRequest createIndexRequest(String index, String type, String id, Map<String, Object> json, String parent, String root) {
        IndexRequest req = new IndexRequest(index, esCfg.indexType, id);

        json.put(esCfg.docTypeField, type);
        if (parent != null && type.equals("NamedEntity"))
            json.put(esCfg.indexJoinField, new HashMap<String, String>() {{
                put("name", type);
                put("parent", parent);
            }});
        else {
            json.put(esCfg.indexJoinField, new HashMap<String, String>() {{
                put("name", type);
            }});
        }
        req = req.source(json);
        return (parent != null) ? req.routing(root) : req;
    }

    public <T extends Entity> T get(String indexName, String id) {
        return get(indexName, id, id);
    }

    @Override
    public <T extends Entity> T get(String indexName, String id, String root) {
        String type = null;
        try {
            final GetRequest req = new GetRequest(indexName, esCfg.indexType, id).routing(root);
            final GetResponse resp = client.get(req);
            if (resp.isExists()) {
                Map<String, Object> sourceAsMap = resp.getSourceAsMap();
                type = (String) sourceAsMap.get(esCfg.docTypeField);
                Class<T> tClass = (Class<T>) Class.forName("org.icij.datashare.text." + type);
                return JsonObjectMapper.getObject(id, sourceAsMap, tClass);
            }
        } catch (IOException e) {
            LOGGER.error("Failed to get entity " + id + " in index " + indexName, e);
        } catch (ClassNotFoundException e) {
            LOGGER.error("no entity for type " + type);
        }
        return null;
    }

    @Override
    public Searcher search(final String indexName, Class<? extends Entity> entityClass) {
        return new ElasticsearchSearcher(client, esCfg, indexName, entityClass);
    }

    @Override
    public boolean createIndex(final String indexName) {
        return ElasticsearchConfiguration.createIndex(client, indexName, esCfg.indexType);
    }

    private static Stream<SearchHit> searchHitStream(Iterable<SearchHit> searchHitIterable) {
        return StreamSupport.stream(searchHitIterable.spliterator(), false);
    }

    private static <T extends Entity> Stream<T> resultStream(Class<T> cls, Iterable<SearchHit> iterable) {
        return searchHitStream(iterable).map( hit -> hitToObject(hit, cls) );
    }

    private static <T extends Entity> T hitToObject(SearchHit searchHit, Class<T> cls) {
        return JsonObjectMapper.getObject(searchHit.getId(), searchHit.getSourceAsMap(), cls);
    }

    public ElasticsearchIndexer withRefresh(WriteRequest.RefreshPolicy refresh) {
        esCfg.withRefresh(refresh);
        return this;
    }

    static class ElasticsearchSearcher implements Searcher {
        private final SearchRequest searchRequest;
        private final BoolQueryBuilder boolQuery;
        private final RestHighLevelClient client;
        private final Class<? extends Entity> cls;
        private final SearchSourceBuilder sourceBuilder;

        ElasticsearchSearcher(RestHighLevelClient client, ElasticsearchConfiguration config, final String indexName, final Class<? extends Entity> cls) {
            this.client = client;
            this.cls = cls;
            sourceBuilder = new SearchSourceBuilder().size(DEFAULT_SEARCH_SIZE);
            searchRequest = new SearchRequest(new String[] {indexName}, sourceBuilder);
            searchRequest.types(config.indexType);
            this.boolQuery = boolQuery().must(matchQuery("type", JsonObjectMapper.getType(cls)));
        }

        @Override
        public Searcher ofStatus(Document.Status status) {
            this.boolQuery.must(matchQuery("status", status.toString()));
            return this;
        }

        @Override
        public Stream<? extends Entity> execute() throws IOException {
            sourceBuilder.query(boolQuery);
            SearchResponse search = client.search(searchRequest);
            return resultStream(this.cls, () -> search.getHits().iterator());
        }

        @Override
        public Searcher withSource(String... fields) {
            sourceBuilder.fetchSource(fields, new String[] {});
            return this;
        }

        @Override
        public Searcher withSource(boolean source) {
            sourceBuilder.fetchSource(false);
            return this;
        }

        @Override
        public Searcher without(Pipeline.Type... nlpPipelines) {
            boolQuery.mustNot(new ConstantScoreQueryBuilder(new TermsQueryBuilder("nerTags",
                              Arrays.stream(nlpPipelines).map(Pipeline.Type::toString).collect(toList()))));
            return this;
        }

        @Override
        public Searcher with(Pipeline.Type... nlpPipelines) {
            boolQuery.must(new ConstantScoreQueryBuilder(new TermsQueryBuilder("nerTags",
                           Arrays.stream(nlpPipelines).map(Pipeline.Type::toString).collect(toList()))));
            return this;
        }

        @Override
        public Searcher limit(int maxCount) {
            sourceBuilder.size(maxCount);
            return this;
        }

        @Override
        public String toString() {
            return "boolQuery : " + boolQuery + " searchRequest : " + searchRequest;
        }
    }
}
