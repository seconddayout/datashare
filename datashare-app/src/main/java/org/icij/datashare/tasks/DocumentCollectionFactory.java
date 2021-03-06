package org.icij.datashare.tasks;

import org.icij.datashare.PropertiesProvider;
import org.icij.extract.queue.DocumentQueue;
import org.icij.extract.report.ReportMap;

public interface DocumentCollectionFactory {
    DocumentQueue createQueue(PropertiesProvider propertiesProvider, String queueName);
    ReportMap createMap(PropertiesProvider propertiesProvider, String mapName);
}
