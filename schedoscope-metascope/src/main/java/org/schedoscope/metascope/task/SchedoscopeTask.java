/**
 * Copyright 2017 Otto (GmbH & Co KG)
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.schedoscope.metascope.task;

import org.apache.tomcat.jdbc.pool.DataSource;
import org.schedoscope.metascope.config.MetascopeConfig;
import org.schedoscope.metascope.exception.SchedoscopeConnectException;
import org.schedoscope.metascope.index.SolrFacade;
import org.schedoscope.metascope.model.*;
import org.schedoscope.metascope.repository.jdbc.RawJDBCSqlRepository;
import org.schedoscope.metascope.task.model.*;
import org.schedoscope.metascope.util.SchedoscopeUtil;
import org.schedoscope.metascope.util.model.SchedoscopeInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;

@Component
public class SchedoscopeTask extends Task {

    private static final Logger LOG = LoggerFactory.getLogger(SchedoscopeTask.class);

    private static final String OCCURRED_AT = "occurred_at";
    private static final String OCCURRED_UNTIL = "occurred_until";
    private static final String SCHEDOSCOPE_TIMESTAMP_FORMAT = "yyyy-MM-dd''T''HH:mm:ss.SSS''Z''";

    @Autowired
    private MetascopeConfig config;
    @Autowired
    private SolrFacade solrFacade;
    @Autowired
    private DataSource dataSource;

    private SchedoscopeInstance schedoscopeInstance;

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean run(RawJDBCSqlRepository sqlRepository, long start) {
        Map<String, MetascopeTable> cachedTables = new HashMap<>();
        Map<String, MetascopeField> cachedFields = new HashMap<>();
        Map<String, MetascopeView> cachedViews = new HashMap<>();
        List<MetascopeView> viewsToPersist = new ArrayList<>();
        List<Dependency> tableDependencies = new ArrayList<>();
        List<Dependency> viewDependencies = new ArrayList<>();
        List<Dependency> fieldDependencies = new ArrayList<>();

        LOG.info("Retrieve and parse data from schedoscope instance \"" + schedoscopeInstance.getId() + "\"");

        Connection connection;
        try {
            connection = dataSource.getConnection();
        } catch (SQLException e) {
            LOG.error("Could not retrieve database connection.", e);
            return false;
        }

        /** get data from schedoscope */
        ViewStatus viewStatus;
        try {
            viewStatus = SchedoscopeUtil.getViewStatus(true, false, null, schedoscopeInstance.getHost(), schedoscopeInstance.getPort());
        } catch (SchedoscopeConnectException e) {
            LOG.error("Could not retrieve view information", e);
            return false;
        }

        if (viewStatus == null) {
            LOG.info("[SchedoscopeSyncTask] FAILED: Schedoscope status information is not available");
            return false;
        }

        if (viewStatus.getViews().size() == 0) {
            LOG.info("[SchedoscopeSyncTask] No schedoscope metadata available. Maybe materialize some views?");
            sqlRepository.saveMetadata(connection, "schedoscopeTimestamp", String.valueOf(System.currentTimeMillis()));
            return false;
        }

        int size = viewStatus.getViews().size();
        LOG.info("Received " + size + " views");

        /** save tables to avoid foreign key constraint violation */
        int tableCount = 0;
        for (View view : viewStatus.getViews()) {
            if (view.isTable() && !view.isExternal()) {
                String fqdn = view.getDatabase() + "." + view.getTableName();
                MetascopeTable table = sqlRepository.findTable(connection, fqdn);
                if (table == null) {
                    table = new MetascopeTable();
                    table.setFqdn(fqdn);
                }
                cachedTables.put(fqdn, table);
                tableCount++;
            }
        }
        LOG.info("Received " + tableCount + " tables");

        for (View view : viewStatus.getViews()) {
            if (view.isTable() && !view.isExternal()) {
                String fqdn = view.getDatabase() + "." + view.getTableName();

                LOG.info("Consuming table " + fqdn);

                MetascopeTable table = cachedTables.get(fqdn);
                table.setSchedoscopeId(schedoscopeInstance.getId());
                table.setDatabaseName(view.getDatabase());
                table.setTableName(view.getTableName());
                table.setViewPath(view.viewPath());
                table.setExternalTable(view.isExternal());
                table.setTableDescription(view.getComment());
                table.setStorageFormat(view.getStorageFormat());
                table.setMaterializeOnce(view.isMaterializeOnce());
                for (ViewField field : view.getFields()) {
                    if (field.getName().equals(OCCURRED_AT)) {
                        table.setTimestampField(OCCURRED_AT);
                        table.setTimestampFieldFormat(SCHEDOSCOPE_TIMESTAMP_FORMAT);
                        break;
                    } else if (field.getName().equals(OCCURRED_UNTIL)) {
                        table.setTimestampField(OCCURRED_UNTIL);
                        table.setTimestampFieldFormat(SCHEDOSCOPE_TIMESTAMP_FORMAT);
                        break;
                    }
                }

                /** fields */
                Set<MetascopeField> tableFields = new HashSet<>();
                int i = 0;
                for (ViewField viewField : view.getFields()) {
                    String fieldFqdn = fqdn + "." + viewField.getName();
                    MetascopeField field = sqlRepository.findField(connection, fieldFqdn);
                    if (field == null) {
                        field = new MetascopeField();
                        field.setFieldId(fieldFqdn);
                        field.setTableFqdn(fqdn);
                    }
                    field.setFieldName(viewField.getName());
                    field.setFieldType(viewField.getFieldtype());
                    field.setFieldOrder(i++);
                    field.setParameter(false);
                    field.setDescription(viewField.getComment());

                    //lineage
                    if (view.getLineage() != null && view.getLineage().get(fieldFqdn) != null) {
                        for (String dependencyField : view.getLineage().get(fieldFqdn)) {
                            if (!dependencyField.equals(fieldFqdn)) {
                                MetascopeField dField = cachedFields.get(dependencyField);
                                if (dField == null) {
                                    dField = new MetascopeField();
                                    dField.setFieldId(dependencyField);
                                    cachedFields.put(dependencyField, dField);
                                }
                                fieldDependencies.add(new Dependency(field.getFieldId(), dField.getFieldId()));
                            }
                        }
                    }

                    tableFields.add(field);
                    cachedFields.put(field.getFieldId(), field);
                }
                table.setFields(tableFields);
                sqlRepository.saveFields(connection, table.getFields(), table.getFqdn(), false);

                /** parameter */
                Set<MetascopeField> tableParameter = new HashSet<>();
                i = 0;
                for (ViewField viewField : view.getParameters()) {
                    String parameterFqdn = fqdn + "." + viewField.getName();
                    MetascopeField parameter = sqlRepository.findField(connection, parameterFqdn);
                    if (parameter == null) {
                        parameter = new MetascopeField();
                        parameter.setFieldId(parameterFqdn);
                        parameter.setTableFqdn(fqdn);
                    }
                    parameter.setFieldName(viewField.getName());
                    parameter.setFieldType(viewField.getFieldtype());
                    parameter.setFieldOrder(i++);
                    parameter.setParameter(true);
                    parameter.setDescription(viewField.getComment());

                    parameter.setTable(table);
                    tableParameter.add(parameter);
                }
                table.setParameters(tableParameter);
                sqlRepository.saveFields(connection, table.getParameters(), table.getFqdn(), true);

                /** exports */
                List<MetascopeExport> tableExports = new ArrayList<>();
                i = 0;
                if (view.getExport() != null) {
                    for (ViewTransformation viewExport : view.getExport()) {
                        String exportFqdn = fqdn + "." + viewExport.getName() + "_" + i;
                        MetascopeExport export = sqlRepository.findExport(connection, exportFqdn);
                        if (export == null) {
                            export = new MetascopeExport();
                            export.setExportId(exportFqdn);
                            export.setTableFqdn(fqdn);
                        }
                        export.setExportType(viewExport.getName());
                        export.setProperties(viewExport.getProperties());

                        export.setTable(table);
                        tableExports.add(export);
                        i++;
                    }
                }
                table.setExports(tableExports);
                sqlRepository.saveExports(connection, table.getExports(), table.getFqdn());

                /** transformation */
                MetascopeTransformation metascopeTransformation = new MetascopeTransformation();
                metascopeTransformation.setTransformationId(fqdn + "." + view.getTransformation().getName());
                metascopeTransformation.setTransformationType(view.getTransformation().getName());
                metascopeTransformation.setProperties(view.getTransformation().getProperties());
                table.setTransformation(metascopeTransformation);
                sqlRepository.saveTransformation(connection, table.getTransformation(), table.getFqdn());

                /** views and dependencies */
                LOG.info("Getting views for table " + fqdn);
                List<View> views = getViewsForTable(table.getViewPath(), viewStatus.getViews());
                LOG.info("Found " + views.size() + " views");

                for (View partition : views) {
                    MetascopeView metascopeView = cachedViews.get(partition.getName());
                    if (metascopeView == null) {
                        metascopeView = new MetascopeView();
                        metascopeView.setViewUrl(partition.getName());
                        metascopeView.setViewId(partition.getName());
                        cachedViews.put(partition.getName(), metascopeView);
                    }
                    viewsToPersist.add(metascopeView);
                    if (table.getParameters() != null && table.getParameters().size() > 0) {
                        String parameterString = getParameterString(partition.getName(), table);
                        metascopeView.setParameterString(parameterString);
                    }
                    for (List<String> dependencyLists : partition.getDependencies().values()) {
                        for (String dependency : dependencyLists) {
                            MetascopeView dependencyView = cachedViews.get(dependency);
                            if (dependencyView == null) {
                                dependencyView = new MetascopeView();
                                dependencyView.setViewUrl(dependency);
                                dependencyView.setViewId(dependency);
                                cachedViews.put(dependency, dependencyView);
                            }
                            metascopeView.addToDependencies(dependencyView);
                            dependencyView.addToSuccessors(metascopeView);
                            viewDependencies.add(new Dependency(metascopeView.getViewId(), dependencyView.getViewId()));
                        }
                    }
                    for (String dependency : partition.getDependencies().keySet()) {
                        tableDependencies.add(new Dependency(fqdn, dependency));
                    }
                    cachedViews.put(partition.getName(), metascopeView);
                    metascopeView.setTable(table);
                }

                LOG.info("Processed all views for table " + fqdn);

                table.setViewsSize(views.size());
                sqlRepository.saveTable(connection, table);

                LOG.info("Finished processing table " + fqdn);
            }
        }

        try {
            LOG.info("Saving field dependency information (" + fieldDependencies.size() + ") ...");
            sqlRepository.insertFieldDependencies(connection, cachedTables.values(), fieldDependencies);
            LOG.info("Saving table dependency information (" + tableDependencies.size() + ") ...");
            sqlRepository.insertTableDependencies(connection, cachedTables.values(), tableDependencies);
            LOG.info("Saving views (" + viewsToPersist.size() + ")...");
            sqlRepository.insertOrUpdateViews(connection, viewsToPersist);
            LOG.info("Saving view dependency information (" + viewDependencies.size() + ") ...");
            sqlRepository.insertViewDependencies(connection, viewDependencies);
        } catch (Exception e) {
            LOG.error("Error writing to database", e);
        }

        LOG.info("Saving to index");
        for (MetascopeTable table : cachedTables.values()) {
            solrFacade.updateTablePartial(table, false);
        }
        solrFacade.commit();
        LOG.info("Finished index update");

        sqlRepository.saveMetadata(connection, "schedoscopeTimestamp", String.valueOf(System.currentTimeMillis()));

        try {
            connection.close();
        } catch (SQLException e) {
            LOG.error("Could not close connection", e);
        }

        LOG.info("Finished sync with schedoscope instance \"" + schedoscopeInstance.getId() + "\"");
        return true;
    }

    private List<View> getViewsForTable(String viewPath, List<View> views) {
        List<View> results = new ArrayList<>();
        for (View view : views) {
            if (view.getName().startsWith(viewPath) && !view.isTable()) {
                results.add(view);
            }
        }
        return results;
    }

    public SchedoscopeTask forInstance(SchedoscopeInstance schedoscopeInstance) {
        this.schedoscopeInstance = schedoscopeInstance;
        return this;
    }

    private String getParameterString(String viewName, MetascopeTable table) {
        String parameterString = null;
        String parametersAsString = viewName.replace(table.getViewPath(), "");
        if (!parametersAsString.isEmpty()) {
            parameterString = new String();
            List<MetascopeField> parameters = table.getOrderedParameters();
            String[] parameterValue = parametersAsString.split("/");
            Iterator<MetascopeField> iterator = parameters.iterator();
            int i = 0;
            while (iterator.hasNext()) {
                parameterString += "/" + iterator.next().getFieldName() + "=" + parameterValue[i];
                i++;
            }
        }
        return parameterString;
    }

}