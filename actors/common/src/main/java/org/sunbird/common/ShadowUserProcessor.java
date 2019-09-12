package org.sunbird.common;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang3.StringUtils;
import org.sunbird.bean.ClaimStatus;
import org.sunbird.bean.ShadowUser;
import org.sunbird.cassandra.CassandraOperation;
import org.sunbird.common.factory.EsClientFactory;
import org.sunbird.common.inf.ElasticSearchService;
import org.sunbird.common.models.response.Response;
import org.sunbird.common.models.util.JsonKey;
import org.sunbird.common.models.util.LoggerEnum;
import org.sunbird.common.models.util.ProjectLogger;
import org.sunbird.common.models.util.ProjectUtil;
import org.sunbird.common.models.util.datasecurity.EncryptionService;
import org.sunbird.dto.SearchDTO;
import org.sunbird.helper.ServiceFactory;
import org.sunbird.learner.util.Util;
import org.sunbird.models.user.UserType;
import scala.concurrent.Future;

import java.sql.Timestamp;
import java.util.*;

public class ShadowUserProcessor {
    private Util.DbInfo usrDbInfo = Util.dbInfoMap.get(JsonKey.USER_DB);
    private CassandraOperation cassandraOperation = ServiceFactory.getInstance();
    private ObjectMapper mapper = new ObjectMapper();
    private EncryptionService encryptionService = org.sunbird.common.models.util.datasecurity.impl.ServiceFactory.getEncryptionServiceInstance(null);
    private ElasticSearchService elasticSearchService = EsClientFactory.getInstance(JsonKey.REST);

    public void process() {
        List<ShadowUser> shadowUserList = getShadowUserFromDb();
        shadowUserList.stream().forEach(singleShadowUser -> {
            processSingleShadowUser(singleShadowUser);
        });
    }

    private void processSingleShadowUser(ShadowUser shadowUser) {
        updateUser(shadowUser);
    }


    private List<Map<String, Object>> getUserFromEs(ShadowUser shadowUser) {
        Map<String, Object> request = new HashMap<>();
        Map<String, Object> filters = new HashMap<>();
        Map<String, Object> or = new HashMap<>();
        or.put(JsonKey.EMAIL, StringUtils.isNotBlank(shadowUser.getEmail()) ? getEncryptedValue(shadowUser.getEmail()) : "");
        or.put(JsonKey.PHONE, StringUtils.isNotBlank(shadowUser.getPhone()) ? getEncryptedValue(shadowUser.getPhone()) : "");
        filters.put(JsonKey.ES_OR_OPERATION, or);
        filters.put(JsonKey.ROOT_ORG_ID, getCustodianOrgId());
        request.put(JsonKey.FILTERS, filters);
        SearchDTO searchDTO = ElasticSearchHelper.createSearchDTO(request);
        Map<String, Object> response = (Map<String, Object>) ElasticSearchHelper.getResponseFromFuture(elasticSearchService.search(searchDTO, JsonKey.USER));
        return (List<Map<String, Object>>) response.get(JsonKey.CONTENT);
    }

    private void updateUser(ShadowUser shadowUser) {
        List<Map<String, Object>> esUser = (List<Map<String, Object>>) getUserFromEs(shadowUser);
        if (CollectionUtils.isNotEmpty(esUser)) {
            if (esUser.size() == 1) {
                Map<String, Object> userMap = esUser.get(0);
                if (!isSame(shadowUser, userMap)) {
                    String rootOrgId = getRootOrgIdFromChannel(shadowUser.getChannel());
                    if (StringUtils.isEmpty(rootOrgId)) {
                        updateUserInShadowDb(shadowUser, ClaimStatus.REJECTED.getValue());
                        return;
                    } else {
                        updateUserInUserTable((String) userMap.get(JsonKey.ID), rootOrgId, shadowUser);
                        String orgIdFromOrgExtId = getOrgId(shadowUser);
                        updateUserOrg(orgIdFromOrgExtId, rootOrgId, userMap);
                        syncUserToES((String) userMap.get(JsonKey.ID));
                        createUserExternalId((String) userMap.get(JsonKey.ID),shadowUser);
                        updateUserInShadowDb(shadowUser, ClaimStatus.CLAIMED.getValue());

                    }
                }
            } else {
                updateUserInShadowDb(shadowUser, ClaimStatus.REJECTED.getValue());
            }
        }

    }

    private void updateUserOrg(String orgIdFromOrgExtId, String rootOrgId, Map<String, Object> userMap) {
        deleteUserOrganisations(userMap);
        registerUserToOrg((String) userMap.get(JsonKey.ID), rootOrgId);
        if (!StringUtils.equals(rootOrgId, orgIdFromOrgExtId)) {
            registerUserToOrg((String) userMap.get(JsonKey.ID), orgIdFromOrgExtId);
        }
    }

    private void updateUserInUserTable(String userId, String rootOrgId, ShadowUser shadowUser) {
        Map<String, Object> propertiesMap = new HashMap<>();
        propertiesMap.put(JsonKey.FIRST_NAME, shadowUser.getName());
        propertiesMap.put(JsonKey.STATUS, shadowUser.getClaimStatus());
        propertiesMap.put(JsonKey.ID, userId);
        propertiesMap.put(JsonKey.UPDATED_BY, shadowUser.getAddedBy());
        propertiesMap.put(JsonKey.UPDATED_ON, new Timestamp(System.currentTimeMillis()));
        if (shadowUser.getClaimStatus() == ProjectUtil.Status.ACTIVE.getValue()) {
            propertiesMap.put(JsonKey.IS_DELETED, false);
        } else {
            propertiesMap.put(JsonKey.IS_DELETED, true);
        }
        propertiesMap.put(JsonKey.USER_TYPE, UserType.TEACHER.getTypeName());
        propertiesMap.put(JsonKey.CHANNEL, shadowUser.getChannel());
        propertiesMap.put(JsonKey.ROOT_ORG_ID, rootOrgId);
        Response response = cassandraOperation.updateRecord(usrDbInfo.getKeySpace(), usrDbInfo.getTableName(), propertiesMap);
        ProjectLogger.log("ShadowUserProcessor:updateUserInUserTable:user is updated ".concat(response.getResult() + ""));
    }


    private String getRootOrgIdFromChannel(String channel) {
        Map<String, Object> request = new HashMap<>();
        Map<String, Object> filters = new HashMap<>();
        filters.put(JsonKey.CHANNEL, channel);
        filters.put(JsonKey.IS_ROOT_ORG, true);
        request.put(JsonKey.FILTERS, filters);
        SearchDTO searchDTO = ElasticSearchHelper.createSearchDTO(request);
        searchDTO.getAdditionalProperties().put(JsonKey.FILTERS, filters);
        Future<Map<String, Object>> esResultF = elasticSearchService.search(searchDTO, ProjectUtil.EsType.organisation.getTypeName());
        Map<String, Object> esResult = (Map<String, Object>) ElasticSearchHelper.getResponseFromFuture(esResultF);
        if (MapUtils.isNotEmpty(esResult) && CollectionUtils.isNotEmpty((List) esResult.get(JsonKey.CONTENT))) {
            Map<String, Object> esContent = ((List<Map<String, Object>>) esResult.get(JsonKey.CONTENT)).get(0);
            return (String) esContent.get(JsonKey.ID);
        }
        return StringUtils.EMPTY;
    }

    private String getEncryptedValue(String key) {
        try {
            return encryptionService.encryptData(key);
        } catch (Exception e) {
            return key;
        }
    }


    /**
     * this method
     *
     * @return
     */
    private String getCustodianOrgId() {
        String custodianOrgId = null;
        Response response = cassandraOperation.getRecordById(JsonKey.SUNBIRD, JsonKey.SYSTEM_SETTINGS_DB, JsonKey.CUSTODIAN_ORG_ID);
        List<Map<String, Object>> result = new ArrayList<>();
        if (!((List) response.getResult().get(JsonKey.RESPONSE)).isEmpty()) {
            result = ((List) response.getResult().get(JsonKey.RESPONSE));
            Map<String, Object> resultMap = result.get(0);
            custodianOrgId = (String) resultMap.get(JsonKey.VALUE);
        }

        if (StringUtils.isBlank(custodianOrgId)) {
            ProjectLogger.log("ShadowUserProcessor:getCustodianOrgId:No CUSTODIAN ORD ID FOUND PLEASE HAVE THAT IN YOUR ENVIRONMENT", LoggerEnum.ERROR.name());
            System.exit(-1);
        }
        return custodianOrgId;
    }

    private List<ShadowUser> getShadowUserFromDb() {
        List<Map<String, Object>> shadowUserList = getRowsFromShadowUserDb();
        List<ShadowUser> shadowUsers = mapper.convertValue(shadowUserList, new TypeReference<List<ShadowUser>>() {
        });
        return shadowUsers;
    }

    /**
     * this method will read rows from the shadow_user table who has status unclaimed
     *
     * @return list
     */
    private List<Map<String, Object>> getRowsFromShadowUserDb() {
        Map<String, Object> proertiesMap = new HashMap<>();
        proertiesMap.put(JsonKey.CLAIM_STATUS, ClaimStatus.UNCLAIMED.getValue());
        Response response = cassandraOperation.getRecordsByProperties(JsonKey.SUNBIRD, JsonKey.SHADOW_USER, proertiesMap);
        List<Map<String, Object>> result = new ArrayList<>();
        if (!((List) response.getResult().get(JsonKey.RESPONSE)).isEmpty()) {
            result = ((List) response.getResult().get(JsonKey.RESPONSE));
        }
        ProjectLogger.log("ShadowUserMigrationScheduler:getRowsFromBulkUserDb:got rows from Bulk user table is:".concat(result.size() + ""), LoggerEnum.INFO.name());
        return result;
    }

    private boolean isSame(ShadowUser shadowUser, Map<String, Object> esUserMap) {
        String orgId = getOrgId(shadowUser);
        if (!shadowUser.getName().equalsIgnoreCase((String) esUserMap.get(JsonKey.FIRST_NAME))) {
            return false;
        }
        if (StringUtils.isNotBlank(orgId) && !getOrganisationIds(esUserMap).contains(orgId)) {
            return false;
        }
        if (shadowUser.getUserStatus() != (int) (esUserMap.get(JsonKey.STATUS))) {
            return false;
        }
        return true;
    }


    private void updateUserInShadowDb(ShadowUser shadowUser, int claimStatus) {
        Map<String, Object> propertiesMap = new HashMap<>();
        propertiesMap.put(JsonKey.CLAIMED_ON, new Timestamp(System.currentTimeMillis()));
        propertiesMap.put(JsonKey.CLAIM_STATUS, claimStatus);
        Map<String, Object> compositeKeysMap = new HashMap<>();
        compositeKeysMap.put(JsonKey.CHANNEL, shadowUser.getChannel());
        compositeKeysMap.put(JsonKey.USER_EXT_ID, shadowUser.getUserExtId());
        Response response = cassandraOperation.updateRecord(JsonKey.SUNBIRD, JsonKey.SHADOW_USER, propertiesMap, compositeKeysMap);
        ProjectLogger.log("ShadowUserProcessor:updateUserInShadowDb:update ".concat(response.getResult() + ""), LoggerEnum.INFO.name());
    }

    private String getOrgId(ShadowUser shadowUser) {
        if (StringUtils.isNotBlank(shadowUser.getOrgExtId())) {
            Map<String, Object> request = new HashMap<>();
            Map<String, Object> filters = new HashMap<>();
            filters.put(JsonKey.EXTERNAL_ID, shadowUser.getOrgExtId());
            filters.put(JsonKey.CHANNEL, shadowUser.getChannel());
            request.put(JsonKey.FILTERS, filters);
            SearchDTO searchDTO = ElasticSearchHelper.createSearchDTO(request);
            Map<String, Object> response = (Map<String, Object>) elasticSearchService.search(searchDTO, ProjectUtil.EsType.organisation.getTypeName());
            List<Map<String, Object>> orgData = ((List<Map<String, Object>>) response.get(JsonKey.CONTENT));
            if (CollectionUtils.isNotEmpty(orgData)) {
                Map<String, Object> orgMap = orgData.get(0);
                return (String) orgMap.get(JsonKey.ID);
            }
        }
        return StringUtils.EMPTY;
    }


    private List<String> getOrganisationIds(Map<String, Object> dbUser) {
        List<String> organisationsIds = new ArrayList<>();
        ((List<Map<String, Object>>) dbUser.get(JsonKey.ORGANISATIONS)).stream().forEach(organisation -> {
            organisationsIds.add((String) organisation.get(JsonKey.ORGANISATION_ID));
        });
        return organisationsIds;
    }

    private void syncUserToES(String userId) {
        Map<String, Object> fullUserDetails = Util.getUserDetails(userId, null);
        try {
            Future<Boolean> future = elasticSearchService.update(JsonKey.USER, userId, fullUserDetails);
            if ((boolean) ElasticSearchHelper.getResponseFromFuture(future)) {
                ProjectLogger.log("ShadowUserMigrationScheduler:updateUserStatus: data successfully updated to elastic search with userId:".concat(userId + ""), LoggerEnum.INFO.name());
            }
        } catch (Exception e) {
            e.printStackTrace();
            ProjectLogger.log("ShadowUserMigrationScheduler:syncUserToES: data failed to updates in elastic search with userId:".concat(userId + ""), LoggerEnum.ERROR.name());
        }
    }


    private void deleteUserOrganisations(Map<String, Object> esUserMap) {
        ((List<Map<String, Object>>) esUserMap.get(JsonKey.ORGANISATIONS)).stream().forEach(organisation -> {
            String id = (String) organisation.get(JsonKey.ID);
            deleteOrgFromUserOrg(id);
        });
    }

    private void deleteOrgFromUserOrg(String id) {
        Response response = cassandraOperation.deleteRecord(JsonKey.SUNBIRD, JsonKey.USER_ORG, id);
        ProjectLogger.log("ShadowUserProcessor:deleteOrgFromUserOrg:user org is deleted ".concat(response.getResult() + ""));
    }


    private void registerUserToOrg(String userId, String organisationId) {
        Map<String, Object> reqMap = new WeakHashMap<>();
        List<String> roles = new ArrayList<>();
        roles.add(ProjectUtil.UserRole.PUBLIC.getValue());
        reqMap.put(JsonKey.ROLES, roles);
        String hashTagId = Util.getHashTagIdFromOrgId(organisationId);
        reqMap.put(JsonKey.HASHTAGID, hashTagId);
        reqMap.put(JsonKey.ID, ProjectUtil.getUniqueIdFromTimestamp(1));
        reqMap.put(JsonKey.USER_ID, userId);
        reqMap.put(JsonKey.ORGANISATION_ID, organisationId);
        reqMap.put(JsonKey.ORG_JOIN_DATE, ProjectUtil.getFormattedDate());
        reqMap.put(JsonKey.IS_DELETED, false);
        Util.DbInfo usrOrgDb = Util.dbInfoMap.get(JsonKey.USR_ORG_DB);
        try {
            cassandraOperation.insertRecord(usrOrgDb.getKeySpace(), usrOrgDb.getTableName(), reqMap);
        } catch (Exception e) {
            ProjectLogger.log(e.getMessage(), e);
        }
    }
    private void createUserExternalId(String userId, ShadowUser shadowUser) {
        Map<String, Object> externalId = new HashMap<>();
        externalId.put(JsonKey.ID_TYPE, shadowUser.getChannel().toLowerCase());
        externalId.put(JsonKey.PROVIDER, shadowUser.getChannel().toLowerCase());
        externalId.put(JsonKey.ID, shadowUser.getUserExtId().toLowerCase());
        externalId.put(JsonKey.ORIGINAL_EXTERNAL_ID, externalId.get(JsonKey.ID));
        externalId.put(JsonKey.ORIGINAL_PROVIDER, externalId.get(JsonKey.PROVIDER));
        externalId.put(JsonKey.ORIGINAL_ID_TYPE, externalId.get(JsonKey.ID_TYPE));
        externalId.put(JsonKey.USER_ID,userId);
        externalId.put(JsonKey.CREATED_BY,shadowUser.getAddedBy());
        externalId.put(JsonKey.CREATED_ON,new Timestamp(System.currentTimeMillis()));
        Response response=cassandraOperation.upsertRecord(JsonKey.SUNBIRD, JsonKey.USR_EXT_IDNT_TABLE, externalId);
        ProjectLogger.log("ShadowUserProcessor:createUserExternalId:response from cassandra ".concat(response.getResult()+""));

    }


}

