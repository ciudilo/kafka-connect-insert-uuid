/*
 * Copyright © 2019 Christopher Matta (chris.matta@gmail.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.github.cjmatta.kafka.connect.smt;

import org.apache.kafka.common.cache.Cache;
import org.apache.kafka.common.cache.LRUCache;
import org.apache.kafka.common.cache.SynchronizedCache;
import org.apache.kafka.common.config.ConfigDef;
import org.apache.kafka.common.config.ConfigException;
import org.apache.kafka.connect.connector.ConnectRecord;
import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;

import org.apache.kafka.connect.transforms.Transformation;
import org.apache.kafka.connect.transforms.util.SchemaUtil;
import org.apache.kafka.connect.transforms.util.SimpleConfig;

import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import static org.apache.kafka.connect.transforms.util.Requirements.requireMap;
import static org.apache.kafka.connect.transforms.util.Requirements.requireStruct;

public abstract class InsertUuid<R extends ConnectRecord<R>> implements Transformation<R> {

  public static final String OVERVIEW_DOC =
    "Insert a random UUID into a connect record";

  private interface ConfigName {
    String UUID_FIELD_NAME = "uuid.field.name";
    String PATTERN = "pattern";
  }

  public static final ConfigDef CONFIG_DEF = new ConfigDef()
    .define(ConfigName.UUID_FIELD_NAME, ConfigDef.Type.STRING, "uuid", ConfigDef.Importance.HIGH,
      "Field name for UUID")
          .define(ConfigName.PATTERN, ConfigDef.Type.STRING, ".*", ConfigDef.Importance.HIGH,
                  "Regex pattern");

  private static final String PURPOSE = "adding UUID to record";

  private String fieldName;
  private Pattern pattern;

  private Cache<Schema, Schema> schemaUpdateCache;

  @Override
  public void configure(Map<String, ?> props) {
    final SimpleConfig config = new SimpleConfig(CONFIG_DEF, props);
    fieldName = config.getString(ConfigName.UUID_FIELD_NAME);

    try {
      pattern = Pattern.compile(config.getString(ConfigName.PATTERN));
    } catch (PatternSyntaxException var4) {
      throw new ConfigException("pattern", pattern, String.format("Could not compile regex '%s'.", pattern));
    }

    schemaUpdateCache = new SynchronizedCache<>(new LRUCache<Schema, Schema>(16));
  }


  @Override
  public R apply(R record) {
    if (operatingSchema(record) == null) {
      return applySchemaless(record);
    } else {
      return applyWithSchema(record);
    }
  }

  private R applySchemaless(R record) {
    final Map<String, Object> value = requireMap(operatingValue(record), PURPOSE);

    final Map<String, Object> updatedValue = new HashMap<>(value);

    updatedValue.put(fieldName, getRandomUuid());

    return newRecord(record, null, updatedValue);
  }

  private R applyWithSchema(R record) {

    final Schema outputSchema = Schema.STRING_SCHEMA;
    String output = new String((byte[]) record.value(), Charset.forName("UTF-8"));

    if (pattern.matcher(output).matches()) {

      return newRecord(record, outputSchema, output.getBytes());

    } else {
      return null;
    }

  }

  @Override
  public ConfigDef config() {
    return CONFIG_DEF;
  }

  @Override
  public void close() {
    schemaUpdateCache = null;
  }

  private String getRandomUuid() {
    return UUID.randomUUID().toString();
  }

  private Schema makeUpdatedSchema(Schema schema) {
    final SchemaBuilder builder = SchemaUtil.copySchemaBasics(schema, SchemaBuilder.struct());

    for (Field field: schema.fields()) {
      builder.field(field.name(), field.schema());
    }

    builder.field(fieldName, Schema.STRING_SCHEMA);

    return builder.build();
  }

  protected abstract Schema operatingSchema(R record);

  protected abstract Object operatingValue(R record);

  protected abstract R newRecord(R record, Schema updatedSchema, Object updatedValue);

  public static class Key<R extends ConnectRecord<R>> extends InsertUuid<R> {

    @Override
    protected Schema operatingSchema(R record) {
      return record.keySchema();
    }

    @Override
    protected Object operatingValue(R record) {
      return record.key();
    }

    @Override
    protected R newRecord(R record, Schema updatedSchema, Object updatedValue) {
      return record.newRecord(record.topic(), record.kafkaPartition(), updatedSchema, updatedValue, record.valueSchema(), record.value(), record.timestamp());
    }

  }

  public static class Value<R extends ConnectRecord<R>> extends InsertUuid<R> {

    @Override
    protected Schema operatingSchema(R record) {
      return record.valueSchema();
    }

    @Override
    protected Object operatingValue(R record) {
      return record.value();
    }

    @Override
    protected R newRecord(R record, Schema updatedSchema, Object updatedValue) {
      return record.newRecord(record.topic(), record.kafkaPartition(), record.keySchema(), record.key(), updatedSchema, updatedValue, record.timestamp());
    }

  }
}


