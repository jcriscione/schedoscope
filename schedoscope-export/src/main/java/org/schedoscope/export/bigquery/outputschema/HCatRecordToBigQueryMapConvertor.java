/**
 * Copyright 2015 Otto (GmbH & Co KG)
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
package org.schedoscope.export.bigquery.outputschema;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.hadoop.hive.serde2.typeinfo.PrimitiveTypeInfo;
import org.apache.hive.hcatalog.common.HCatException;
import org.apache.hive.hcatalog.data.DefaultHCatRecord;
import org.apache.hive.hcatalog.data.HCatRecord;
import org.apache.hive.hcatalog.data.schema.HCatFieldSchema;
import org.apache.hive.hcatalog.data.schema.HCatSchema;
import org.schedoscope.export.utils.HCatSchemaToBigQueryTransformer;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.schedoscope.export.utils.HCatSchemaToBigQueryTransformer.transformSchema;


/**
 * Convert HCat records to maps for use with BigQuery APIs
 */
public class HCatRecordToBigQueryMapConvertor {

    static private final ObjectMapper jsonConvertor = new ObjectMapper();

    private static final HCatSchemaToBigQueryTransformer.Constructor<HCatRecord, Object, Pair<String, Object>, Map<String, Object>> c = new HCatSchemaToBigQueryTransformer.Constructor<HCatRecord, Object, Pair<String, Object>, Map<String, Object>>() {

        @Override
        public Object accessPrimitiveField(HCatSchema schema, HCatFieldSchema field, HCatRecord hCatRecord) {
            try {
                return hCatRecord.get(field.getName(), schema);
            } catch (HCatException e) {
                // not going to happen
                return null;
            }
        }

        @Override
        public Object accessMapField(HCatSchema schema, HCatFieldSchema field, HCatRecord hCatRecord) {
            try {
                return hCatRecord.getMap(field.getName(), schema);
            } catch (HCatException e) {
                // not going to happen
                return null;
            }
        }

        @Override
        public HCatRecord accessStructField(HCatSchema schema, HCatFieldSchema field, HCatRecord hCatRecord) {
            try {
                List<Object> structFields = (List<Object>) hCatRecord.getStruct(field.getName(), schema);
                return structFields == null ? null : new DefaultHCatRecord(structFields);
            } catch (HCatException e) {
                // not going to happen
                return null;
            }
        }

        @Override
        public List<Object> accessPrimitiveArrayField(HCatSchema schema, HCatFieldSchema field, HCatRecord hCatRecord) {
            try {
                return (List<Object>) hCatRecord.getList(field.getName(), schema);
            } catch (HCatException e) {
                // not going to happen
                return null;
            }
        }

        @Override
        public List<Object> accessArrayArrayField(HCatSchema schema, HCatFieldSchema field, HCatRecord hCatRecord) {
            return accessPrimitiveArrayField(schema, field, hCatRecord);
        }

        @Override
        public List<Object> accessMapArrayField(HCatSchema schema, HCatFieldSchema field, HCatRecord hCatRecord) {
            return accessPrimitiveArrayField(schema, field, hCatRecord);
        }

        @Override
        public List<HCatRecord> accessStructArrayField(HCatSchema schema, HCatFieldSchema field, HCatRecord hCatRecord) {
            List<Object> fieldValue = accessPrimitiveArrayField(schema, field, hCatRecord);
            return fieldValue == null ?
                    null : fieldValue
                    .stream()
                    .map(s -> s == null ? null : new DefaultHCatRecord((List<Object>) s))
                    .collect(Collectors.toList());
        }

        @Override
        public Map<String, Object> constructSchema(List<Pair<String, Object>> pairs) {

            if (pairs == null)
                return null;

            Map<String, Object> m = new HashMap<>();

            for (Pair<String, Object> p : pairs)
                if (p != null && p.getValue() != null)
                    m.put(p.getKey(), p.getValue());

            return m;
        }

        @Override
        public Pair<String, Object> constructPrimitiveField(HCatFieldSchema field, Object o) {
            return new ImmutablePair<>(field.getName(), o);
        }

        @Override
        public Pair<String, Object> constructMapField(HCatFieldSchema field, Object o) {
            try {
                return new ImmutablePair<>(field.getName(), o == null ? null : jsonConvertor.writeValueAsString(o));
            } catch (JsonProcessingException e) {
                // should not happen
                return null;
            }
        }

        @Override
        public Pair<String, Object> constructStructField(HCatSchema schema, HCatFieldSchema field, Map<String, Object> stringObjectMap) {
            return new ImmutablePair<>(field.getName(), stringObjectMap);
        }

        @Override
        public Pair<String, Object> constructPrimitiveArrayField(HCatFieldSchema field, PrimitiveTypeInfo elementType, List<Object> objects) {
            return new ImmutablePair<>(field.getName(), objects);
        }

        @Override
        public Pair<String, Object> constructMapArrayField(HCatFieldSchema field, List<Object> objects) {
            return new ImmutablePair<>(field.getName(),
                    objects == null ?
                            null : objects.stream()
                            .map(m -> {
                                try {
                                    return jsonConvertor.writeValueAsString(m);
                                } catch (JsonProcessingException e) {
                                    // should not happen
                                    return null;
                                }
                            })
                            .collect(Collectors.toList())
            );
        }

        @Override
        public Pair<String, Object> constructArrayArrayField(HCatFieldSchema field, List<Object> objects) {
            return new ImmutablePair<>(field.getName(),
                    objects == null ?
                            null : objects.stream()
                            .map(a -> {
                                try {
                                    return jsonConvertor.writeValueAsString(a);
                                } catch (JsonProcessingException e) {
                                    // should not happen
                                    return null;
                                }
                            })
                            .collect(Collectors.toList())
            );
        }

        @Override
        public Pair<String, Object> constructStructArrayField(HCatSchema schema, HCatFieldSchema field, List<Map<String, Object>> maps) {
            return maps == null ? null : new ImmutablePair<>(field.getName(), maps);
        }
    };


    /**
     * Given an HCat schema, convert a record to a map representation for use with the BigQuery API.
     *
     * @param schema the HCatSchema to which records conform
     * @param record the record to transform.
     * @return a nested map representing the record sucht that it can be converted to the JSON format expected by
     * the BigQuery API.
     */
    static public Map<String, Object> convertHCatRecordToBigQueryMap(HCatSchema schema, HCatRecord record) {
        return transformSchema(c, schema, record);
    }
}
