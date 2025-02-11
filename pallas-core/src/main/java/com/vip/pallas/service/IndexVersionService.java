/**
 * Copyright 2019 vip.com.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * </p>
 */

package com.vip.pallas.service;

import static java.util.stream.Collectors.toMap;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.*;

import javax.annotation.Resource;

import com.vip.pallas.entity.MappingParentType;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import com.vip.pallas.bean.DBSchema;
import com.vip.pallas.bean.IndexParam;
import com.vip.pallas.bean.IndexVersion.VersionField;
import com.vip.pallas.exception.PallasException;
import com.vip.pallas.mybatis.entity.Cluster;
import com.vip.pallas.mybatis.entity.DataSource;
import com.vip.pallas.mybatis.entity.Index;
import com.vip.pallas.mybatis.entity.IndexVersion;
import com.vip.pallas.mybatis.entity.Mapping;
import com.vip.pallas.mybatis.entity.Page;
import com.vip.pallas.mybatis.repository.ClusterRepository;
import com.vip.pallas.mybatis.repository.DataSourceRepository;
import com.vip.pallas.mybatis.repository.IndexRepository;
import com.vip.pallas.mybatis.repository.IndexVersionRepository;
import com.vip.pallas.mybatis.repository.MappingRepository;
import com.vip.pallas.service.impl.ElasticSearchServiceImpl;
import com.vip.pallas.utils.JdbcUtil;
import com.vip.pallas.utils.JsonUtil;
import com.vip.pallas.utils.SqlTypeUtil;

public abstract class IndexVersionService {
	
	private static final Logger logger = LoggerFactory.getLogger(IndexVersionService.class);

	@Resource
	private IndexRepository indexRepository;

	@Resource
	private ClusterRepository clusterRepository;

	@Autowired
	private ElasticSearchService elasticSearchService;

	@Resource
	private IndexVersionRepository indexVersionRepository;

	@Resource
	private DataSourceRepository dataSourceRepository;

	@Resource
	private MappingRepository mappingRepository;
	
	@Resource
	private IndexService indexService;

	public void updateSyncState(Long id, boolean isSync) {
		indexVersionRepository.updateSyncState(id, isSync);
	}
	public void insert(IndexVersion indexVersion, ArrayList schemaArrayNode) {
		Date date = new Date();
		indexVersion.setIsUsed(Boolean.FALSE);
		indexVersion.setSyncStat("full_ready");
		indexVersion.setCreateTime(date);
		indexVersion.setUpdateTime(date);
		indexVersion.setIsSync(Boolean.FALSE);
		indexVersionRepository.insert(indexVersion);

		insertMappings(indexVersion, schemaArrayNode);
	}


	public IndexVersion findById(Long id) {
		return indexVersionRepository.selectByPrimaryKey(id);
	}

	public List<IndexVersion> findAll() {
		return indexVersionRepository.selectAll();
	}

	public String getRampupByVersionId(Long versionId) {
		return indexVersionRepository.findRampupByVersionId(versionId);
	}

	public void enableVersion(Long versionId) throws Exception {
		IndexVersion indexVersion = this.findById(versionId);

		if (indexVersion == null) {
			throw new PallasException("根据versionId: " + versionId + "找不到相应索引版本");
		}

		Index index = indexRepository.selectByid(indexVersion.getIndexId());
		String indexName = index.getIndexName();
		List<Cluster> clusters = clusterRepository.selectPhysicalClustersByIndexId(index.getId());
		try {
			for (Cluster cluster : clusters) {
				if (!elasticSearchService.isExistIndex(indexName, cluster.getHttpAddress(), versionId)) {
					throw new PallasException("ES索引:" + indexName + "_" + versionId + "不存在，请先同步(创建)索引版本");
				}
				elasticSearchService.transferAliasIndex(index.getId(), indexName, versionId, cluster);
			}
			// update is_used
			indexVersionRepository.enableThisVersionAndDisableOthers(indexVersion.getIndexId(), indexVersion.getId());
		} catch (Exception e) {
			logger.error(e.getClass() + " " + e.getMessage(), e);
		}
	}
	
	public List<DBSchema> getMetaDataFromDB(Long indexId) throws SQLException, PallasException {
		Connection conn = null;
		Statement stmt = null;
		ResultSet rs = null;

		List<DataSource> dataSourceList = dataSourceRepository.selectByIndexId(indexId);

		if (dataSourceList == null || dataSourceList.size() == 0) {
			return Collections.emptyList();
		}
		// get the first one as the schema source.
		DataSource dataSource = dataSourceList.get(0);

		try {
			conn = JdbcUtil.getConnection(
					"jdbc:mysql://" + dataSource.getIp() + ":" + dataSource.getPort() + "/" + dataSource.getDbname()
							+ "?useUnicode=true&characterEncoding=utf-8&tinyInt1isBit=false",
					dataSource.getUsername(), indexService.decodePassword(dataSource.getPassword()));
			stmt = conn.createStatement();
			rs = stmt.executeQuery(
					"select * from " + dataSource.getDbname() + "." + dataSource.getTableName() + " LIMIT 0,0");
			ResultSetMetaData rsmd = rs.getMetaData();

			int columnCount = rsmd.getColumnCount();
			List<DBSchema> list = new ArrayList<>(columnCount);

			for (int i = 0; i < columnCount; i++) {
				list.add(
						new DBSchema(rsmd.getColumnName(i + 1), SqlTypeUtil.getTypeByValue(rsmd.getColumnType(i + 1))));
			}

			return list;
		} finally {
			JdbcUtil.free(conn, stmt, rs);
		}
	}

	public List<IndexVersion> findPage(Page<IndexVersion> page, Long indexId) {
		Map<String, Object> params = new HashMap<String, Object>();
		params.put("indexId", indexId);
		page.setParams(params);

		return indexVersionRepository.selectPage(page);
	}

	//Indev Version 启用中 (is_used = 1)
 	public void disableVersion(Long versionId) throws Exception {
		IndexVersion indexVersion = this.findById(versionId);

		if (indexVersion != null) {
			Index index = indexRepository.selectByid(indexVersion.getIndexId());
			List<Cluster> clusters = clusterRepository.selectPhysicalClustersByIndexId(index.getId());
			for (Cluster cluster : clusters) {
				elasticSearchService.deleteAliasIndexByCluster(index.getId(), index.getIndexName(), versionId, cluster);
				elasticSearchService.deleteIndex(cluster.getHttpAddress(), index.getIndexName() + "_" + versionId);
			}
			indexVersionRepository.disableVersion(versionId);
		}
	}

	//Indev Version未启用
	public void disableVersionSync(Long indexId, Long versionId) throws Exception {
		Index index = indexService.findById(indexId);
		if(index != null) {
			elasticSearchService.deleteIndex(index.getIndexName(), index.getId(), versionId);
			updateSyncState(versionId, false);
		}

	}

	public void update(IndexVersion indexVersion){
		indexVersionRepository.updateByPrimaryKeySelective(indexVersion);
	}

	public  List<Mapping> updateDynamic(IndexVersion indexVersion,ArrayList schemaArrayNode) {
		Date date = new Date();
		indexVersion.setUpdateTime(date);
		indexVersionRepository.updateByPrimaryKeySelective(indexVersion);
		return insertMappings(indexVersion, schemaArrayNode);
	}

	public void update(IndexVersion indexVersion, ArrayList schemaArrayNode) {
		Date date = new Date();
		indexVersion.setUpdateTime(date);
		indexVersionRepository.updateByPrimaryKeySelective(indexVersion);

		mappingRepository.deleteByVersionId(indexVersion.getId());

		insertMappings(indexVersion, schemaArrayNode);
	}

	private List<Mapping> insertMappings(IndexVersion indexVersion, ArrayList<Map> schemaArrayNode) {
		List<Mapping> mappingList = new ArrayList<>(schemaArrayNode.size());
		for (Map node : schemaArrayNode) {
			Mapping mapping = initMapping(node, indexVersion);
			Boolean dynamicNode = (Boolean) node.get("dynamic");
			mapping.setDynamic(dynamicNode != null && dynamicNode);
			mappingList.add(mapping);
			mappingRepository.insert(mapping);
			// 检查是否有多域
			mappingList.addAll(checkAndAddMultiFieldsMapping(node, mapping.getId(), indexVersion));
			List<Map> childNode = (List<Map>) node.get("children");
			for (Map _childNode : childNode) {
				Mapping childMapping = initMapping(_childNode, indexVersion);
				childMapping.setParentId(mapping.getId());
				childMapping.setDynamic(Boolean.FALSE);
				if (mapping.getFieldType().equals("object")){
					childMapping.setParentType(MappingParentType.OBJECT.val());
				}else {
					childMapping.setParentType(MappingParentType.NESTED.val());
				}
				mappingList.add(childMapping);
				mappingRepository.insert(childMapping);
				// 检查nested域是否有多域
				mappingList.addAll(checkAndAddMultiFieldsMapping(_childNode, childMapping.getId(), indexVersion));
			}
		}
		return mappingList;
	}

	private List<Mapping> checkAndAddMultiFieldsMapping(Map map, Long parentId, IndexVersion indexVersion){
		if (null == map.get("multiField"))return Collections.emptyList();

		List<Map> multiFieldNodes = (List<Map>) map.get("multiField");
		List<Mapping> mappings = new ArrayList<>(multiFieldNodes.size());
		// 增加multi-field
		for (Map _node : multiFieldNodes) {
			Mapping multiFieldsMapping = initMapping(_node, indexVersion);
			multiFieldsMapping.setParentId(parentId);
			multiFieldsMapping.setDynamic(Boolean.FALSE);
			multiFieldsMapping.setParentType(MappingParentType.MULTI_FIELDS.val());
			mappings.add(multiFieldsMapping);
			mappingRepository.insert(multiFieldsMapping);
		}
		return mappings;
	}

	private Mapping initMapping(Map node, IndexVersion indexVersion) {
		Mapping mapping = new Mapping();
		mapping.setVersionId(indexVersion.getId());
		mapping.setFieldName((String) node.get("fieldName"));
		mapping.setFieldType((String) node.get("fieldType"));
		mapping.setMulti((Boolean) node.get("multi"));
		mapping.setSearch((Boolean) node.get("search"));
		mapping.setDocValue((Boolean) node.get("docValue"));
		if (node.containsKey("store")&&((Boolean) node.get("store"))==Boolean.TRUE){
			mapping.setStore((Boolean) node.get("store"));
		}
		mapping.setCreateTime(indexVersion.getUpdateTime());
		mapping.setUpdateTime(indexVersion.getUpdateTime());
		if (node.get("copyTo") != null){
			List<String> copyTo = (List<String>)node.get("copyTo");
			if (!copyTo.isEmpty()){
				mapping.setCopyTo(String.join(",", copyTo));
			}
		}
		return mapping;
	}

	public Map<String, String> getSchemaMappingAsMap(long versionId) throws SQLException, PallasException {
		com.vip.pallas.bean.IndexVersion v = findVersionById(versionId);
		if (v == null || v.getSchema() == null) {
			return new HashMap<>();
		}
		return v.getSchema().stream().collect(toMap((VersionField f) -> f.getFieldName(),
				(VersionField f) -> f.getFieldType().toLowerCase(), (String oldO, String newO) -> oldO));
	}

	public com.vip.pallas.bean.IndexVersion findVersionById(long versionId) throws SQLException, PallasException {
		IndexVersion indexVersion = indexVersionRepository.selectByPrimaryKey(versionId);

		com.vip.pallas.bean.IndexVersion version = new com.vip.pallas.bean.IndexVersion();

		version.setId(indexVersion.getId());
		version.setIdField(indexVersion.getIdField());
		version.setIndexId(indexVersion.getIndexId());
		version.setReplicationNum(indexVersion.getNumOfReplication());
		version.setRoutingKey(indexVersion.getRoutingKey());
		version.setShardNum(indexVersion.getNumOfShards());
		version.setUpdateTimeField(indexVersion.getUpdateTimeField());
		version.setVdpQueue(indexVersion.getVdpQueue());
		version.setCheckSum(indexVersion.getCheckSum());
		version.setFilterFields(indexVersion.getFilterFields());
		version.setPreferExecutor(indexVersion.getPreferExecutor());
		version.setVdp(indexVersion.getVdp());
		version.setRealClusterIds(indexVersion.getRealClusterIds());
		version.setAllocationNodes(indexVersion.getAllocationNodes());
		version.setDynamic(indexVersion.getDynamic());
		version.setSync(indexVersion.getIsSync());
		version.setIndexSlowThreshold(indexVersion.getIndexSlowThreshold());
		version.setFetchSlowThreshold(indexVersion.getFetchSlowThreshold());
		version.setQuerySlowThreshold(indexVersion.getQuerySlowThreshold());
		version.setRefreshInterval(indexVersion.getRefreshInterval());

		version.setMaxResultWindow(indexVersion.getMaxResultWindow());
		version.setTotalShardsPerNode(indexVersion.getTotalShardsPerNode());
		version.setFlushThresholdSize(indexVersion.getFlushThresholdSize());
		version.setTranslogDurability(indexVersion.getTranslogDurability());
		version.setSyncInterval(indexVersion.getSyncInterval());
		version.setSourceDisabled(indexVersion.getSourceDisabled());
		version.setSourceIncludes(indexVersion.getSourceIncludes());
		version.setSourceExcludes(indexVersion.getSourceExcludes());

		// dual with mapping
		List<Mapping> mappingList = mappingRepository.selectByVersionId(versionId);

		if (mappingList != null && mappingList.size() > 0) {
			Map<Long, List<Mapping>> mappingMap = new HashMap<>();
			List<Mapping> firstLayerList = new ArrayList<>();

			ElasticSearchServiceImpl.constructMappings(mappingList, firstLayerList, mappingMap);

			// dual with DBSchema
			List<DBSchema> schemaList = this.getMetaDataFromDB(indexVersion.getIndexId());
			Map<String, String> schemaMap = new HashMap<String, String>();
			if (schemaList != null) {
				for (DBSchema schema : schemaList) {
					schemaMap.put(schema.getDbFieldName(), schema.getDbFieldType());
				}
			}

			for (Mapping mapping : firstLayerList) {
				VersionField field = initVersionFieldByMapping(mapping);
				field.setDbFieldType(schemaMap.get(mapping.getFieldName()));
				version.addField(field);
				// 检查是否有嵌套字段或者multi_field
				checkAndFillNestedOrMultiField(field, mapping, mappingMap);
			}
		}
		return version;
	}

	private void checkAndFillNestedOrMultiField(VersionField parentField, Mapping parentMapping, Map<Long, List<Mapping>> mappingMap){
		List<Mapping> subMappings = mappingMap.get(parentMapping.getId());
		if (subMappings != null) {
			for (Mapping subFieldMapping : subMappings) {
				VersionField subField = initVersionFieldByMapping(subFieldMapping);
				if (subFieldMapping.getParentType() == MappingParentType.MULTI_FIELDS.val()){
					parentField.addMultiField(subField);
				}else {
					parentField.addField(subField);
					// 如果是nested或者object field，检查是否有下层
					checkAndFillNestedOrMultiField(subField, subFieldMapping, mappingMap);
				}
			}
		}
	}

	public VersionField initVersionFieldByMapping(Mapping mapping){
		VersionField field = new VersionField();
		field.setFieldType(mapping.getFieldType());
		field.setDocValue(mapping.getDocValue());
		field.setSearch(mapping.getSearch());
		field.setFieldName(mapping.getFieldName());
		field.setMulti(mapping.getMulti());
		field.setDynamic(mapping.getDynamic());
		if (mapping.getStore()!=null){
			field.setStore(mapping.getStore());
		}
		if (StringUtils.isNotBlank(mapping.getCopyTo())){
			field.setCopyTo(Arrays.asList(mapping.getCopyTo().split("\\s*,\\s*")));
		}
		return field;
	}

	public com.vip.pallas.bean.IndexVersion copyVersion(Long indexId, Long versionId)
			throws SQLException, PallasException {
		com.vip.pallas.bean.IndexVersion version = this.findVersionById(versionId);
		List<VersionField> fieldList = version.getSchema();

		Map<String, VersionField> fieldMap = fieldList.stream()
				.collect(toMap(field -> field.getFieldName() + "#" + field.getDbFieldType(), field -> field));

		List<DBSchema> schemaList = this.getMetaDataFromDB(indexId);
        initVersionSchemaByDBSchemaList(version, schemaList, fieldMap);
		return version;
	}

	public void deleteVersion(Long versionId) throws Exception {
		indexVersionRepository.deleteVersion(versionId);
		mappingRepository.deleteByVersionId(versionId);
	}

	public List<IndexParam> selectUsed() {
		return indexVersionRepository.selectUsed();
	}

	public String importSchema(String importSchema, Long indexId) throws Exception {
		Map<String, Object> map = JsonUtil.readValue(importSchema, Map.class);
		List<VersionField> fieldList = JsonUtil.readValue(JsonUtil.toJson(map.get("schema")), List.class,
				VersionField.class);
		Map<String, VersionField> fieldMap = fieldList.stream()
				.collect(toMap(field -> field.getFieldName() + "#" + field.getDbFieldType(), field -> field));

		List<DBSchema> schemaList;

		try {
			schemaList = this.getMetaDataFromDB(indexId);
		} catch (Exception e) {
			logger.error(e.toString(), e);
			return importSchema;
		}
		com.vip.pallas.bean.IndexVersion version = new com.vip.pallas.bean.IndexVersion();

        initVersionSchemaByDBSchemaList(version, schemaList, fieldMap);
		Map<String, Object> resultMap = new HashMap<String, Object>();
		resultMap.put("schema", version.getSchema());

		return JsonUtil.toJson(resultMap);
	}

	private void initVersionSchemaByDBSchemaList(com.vip.pallas.bean.IndexVersion version,List<DBSchema> schemaList,Map<String, VersionField> fieldMap){
		List<VersionField> newSchema = new ArrayList<>();
        for (DBSchema dbSchema : schemaList) {
            VersionField field = new VersionField();
            field.setFieldName(dbSchema.getDbFieldName());
            field.setDbFieldType(dbSchema.getDbFieldType());
            String fieldKey = field.getFieldName() + "#" + field.getDbFieldType();
            if (fieldMap.containsKey(fieldKey)) {
                VersionField versionField = fieldMap.remove(fieldKey);
                copyVersionField(versionField,field);
            } else {
                convertFieldType(field);
            }
            newSchema.add(field);
        }
        // DB 不存在的那些schema 也要导入
		for (VersionField field : fieldMap.values()) {
			newSchema.add(field);
		}
        version.setSchema(newSchema);
    }

	private void copyVersionField(VersionField srcField, VersionField distField){
		distField.setFieldType(srcField.getFieldType());
		distField.setDocValue(srcField.isDocValue());
		distField.setStore(srcField.isStore());
		distField.setSearch(srcField.isSearch());
		distField.setMulti(srcField.isMulti());
		distField.setDynamic(srcField.isDynamic());
		distField.setChildren(srcField.getChildren());
		distField.setCopyTo(srcField.getCopyTo());
		distField.setMultiField(srcField.getMultiField());
	}

	private void convertFieldType(VersionField field){
		switch (field.getDbFieldType()) {
			case "TINYINT":
			case "SMALLINT":
				field.setFieldType("keyword_as_number");
				break;
			case "INTEGER":
				field.setFieldType("integer");
				break;
			case "BIGINT":
				field.setFieldType("long");
				break;
			case "DATE":
			case "TIMESTAMP":
				field.setFieldType("date");
				break;
			case "DOUBLE":
			case "DECIMAL":
				field.setFieldType("double");
				break;
			default:
				field.setFieldType("keyword");
				break;
		}
	}

	public IndexVersion findUsedIndexVersionByIndexId(Long indexId) {
		return indexVersionRepository.findUsedIndexVersionByIndexId(indexId);
	}

	public  com.vip.pallas.bean.IndexVersion findUsedOrLastIndexVersoinByIndexId(Long indexId) throws PallasException, SQLException {
		IndexVersion version = indexVersionRepository.findUsedOrLastIndexVersoinByIndexId(indexId);
		return null==version?null:findVersionById(version.getId());
	}
}