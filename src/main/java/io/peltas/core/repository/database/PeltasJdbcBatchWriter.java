/**
 * Copyright 2019 Pleo Soft d.o.o. (pleosoft.com)

 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0

 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.peltas.core.repository.database;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.item.ItemWriter;
import org.springframework.core.io.Resource;
import org.springframework.integration.transformer.ObjectToMapTransformer;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.GenericMessage;
import org.springframework.util.FileCopyUtils;
import org.springframework.util.StringUtils;

import com.google.common.collect.ImmutableMap;

import io.peltas.core.alfresco.PeltasEntry;
import io.peltas.core.alfresco.config.PipelineCollection;
import io.peltas.core.batch.PeltasDataHolder;

public class PeltasJdbcBatchWriter implements ItemWriter<PeltasDataHolder> {

	private static final Logger LOGGER = LoggerFactory.getLogger(PeltasJdbcBatchWriter.class);

	private final NamedParameterJdbcOperations namedParameterJdbcTemplate;

	private final Map<String, String> mappedExecutionsConfigResources;

	public PeltasJdbcBatchWriter(NamedParameterJdbcTemplate template, Resource[] resources) {
		this.namedParameterJdbcTemplate = template;

		this.mappedExecutionsConfigResources = new HashMap<>();

		try {
			for (Resource resource : resources) {
				String filename = resource.getFilename();
				String configKey = StringUtils.getFilenameExtension(filename);
				if (!"sql".equals(configKey)) {
					continue;
				}

				String key = filename.substring(0, filename.length() - configKey.length() - 1);

				// Map<String, String> config = new HashMap<>();
				try (InputStream is = resource.getInputStream()) {
					String configValue = FileCopyUtils.copyToString(new InputStreamReader(is));

					// config.put(configKey, configValue);
					this.mappedExecutionsConfigResources.put(key, configValue);
				}

			}
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	@SuppressWarnings("unchecked")
	public void write(List<? extends PeltasDataHolder> items) throws Exception {

		for (PeltasDataHolder item : items) {
			MapSqlParameterSource parameterSourceMap = createSqlParameterSource(item);

			List<String> executions = item.getConfig().getPipeline().getExecutions();

			for (String executionKey : executions) {
				String sql = mappedExecutionsConfigResources.get(executionKey);
				Map<String, Object> sqlResult = namedParameterJdbcTemplate.queryForMap(sql, parameterSourceMap);
				addSources(executionKey, parameterSourceMap, sqlResult);

				if (LOGGER.isTraceEnabled()) {
					LOGGER.trace("doWithItem() executing sql: {} with data {}", sql, parameterSourceMap.getValues());
				}
			}

			Map<String, PipelineCollection> collections = item.getConfig().getPipeline().getCollections();
			if (collections != null) {
				LOGGER.debug("doWithItem() executing collections: {}", collections);

				Set<Entry<String, PipelineCollection>> collectionEntrySet = collections.entrySet();
				for (Entry<String, PipelineCollection> collectionEntry : collectionEntrySet) {
					String collectionKey = collectionEntry.getKey();

					PipelineCollection pipelineCollection = collectionEntry.getValue();
					List<String> collectionExecutions = pipelineCollection.getExecutions();

					Collection<Object> collectionValueList = (Collection<Object>) item.getBuilder().get(collectionKey);

					if (collectionValueList != null && !collectionValueList.isEmpty()) {

						MapSqlParameterSource collectionSqlMapsource = new MapSqlParameterSource(
								parameterSourceMap.getValues());

						for (Object collectionValue : collectionValueList) {
							if (collectionValue instanceof Map) {
								Message<Map<String, ?>> message = new GenericMessage<Map<String, ?>>(
										ImmutableMap.of(collectionKey, collectionValue));
								ObjectToMapTransformer transformer = new ObjectToMapTransformer();
								transformer.setShouldFlattenKeys(true);
								Map<String, ?> payload = (Map<String, ?>) transformer.transform(message).getPayload();
								collectionSqlMapsource.addValues(payload);
							} else {
								collectionSqlMapsource.addValue(collectionKey, collectionValue);
							}

							for (String executionKey : collectionExecutions) {
								String collectionSql = mappedExecutionsConfigResources.get(executionKey);
								Map<String, Object> sqlResult = namedParameterJdbcTemplate.queryForMap(collectionSql,
										collectionSqlMapsource);
								addSources(executionKey, collectionSqlMapsource, sqlResult);
								if (LOGGER.isTraceEnabled()) {
									LOGGER.trace("doWithItem() executing collection sql in batch: {}", collectionSql);
								}
							}
						}
					}
				}
			}
		}
	}

	private void addSources(String executionKey, MapSqlParameterSource parameterSourceMap, Map<String, Object> key) {
		Set<Entry<String, Object>> entrySet = key.entrySet();

		Map<String, Object> keyMap = new HashMap<>();
		for (Entry<String, Object> keyEntry : entrySet) {
			keyMap.put(executionKey + "." + keyEntry.getKey(), keyEntry.getValue());
		}

		if (keyMap.size() > 0) {
			parameterSourceMap.addValues(keyMap);
		}
	}

	public static MapSqlParameterSource createSqlParameterSource(PeltasDataHolder item) {
		PeltasEntry auditEntry = item.getAuditEntry();
		Map<String, Object> builder = item.getBuilder();

		MapSqlParameterSource parameterSourceMap = new MapSqlParameterSource();

		// TODO FIXME: getUserFullname ?
//		ImmutableMap<String, ? extends Object> auditMap = ImmutableMap.of("audit.id", auditEntry.getId(), "audit.user",
//				auditEntry.getUser(), "audit.userfull", auditEntry.getUserFullname(), "audit.time",
//				auditEntry.getTime());

		ImmutableMap<String, ? extends Object> auditMap = ImmutableMap.of("audit.id", auditEntry.getId(), "audit.user",
				auditEntry.getUser(), "audit.time", auditEntry.getTime());

		parameterSourceMap.addValues(builder);
		parameterSourceMap.addValues(auditMap);
		return parameterSourceMap;
	}
}