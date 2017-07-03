package org.sunbird.learner.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.sunbird.common.exception.ProjectCommonException;
import org.sunbird.common.models.util.*;
import org.sunbird.common.responsecode.ResponseCode;
import org.sunbird.dto.SearchDTO;
import org.sunbird.helper.CassandraConnectionManager;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.util.*;

/**
 * Utility class for actors
 * @author arvind .
 */
public class Util {

    public static Map<String, DbInfo> dbInfoMap = new HashMap<String, DbInfo>();
    private static LogHelper logger = LogHelper.getInstance(Util.class.getName());
    private static final String KEY_SPACE_NAME = "sunbird";
    private static Properties prop = new Properties();
    private static Map<String, String> headers = new HashMap<String, String>();

    static {
        loadPropertiesFile();

        //setting db info (keyspace , table) into static map
        dbInfoMap.put(JsonKey.LEARNER_COURSE_DB, getDbInfoObject("sunbird","course_enrollment"));
        dbInfoMap.put(JsonKey.LEARNER_CONTENT_DB, getDbInfoObject("sunbird","content_consumption"));
        dbInfoMap.put(JsonKey.COURSE_MANAGEMENT_DB, getDbInfoObject("sunbird","course_management"));
        dbInfoMap.put(JsonKey.USER_DB, getDbInfoObject("sunbird","user"));
        dbInfoMap.put(JsonKey.USER_AUTH_DB, getDbInfoObject("sunbird","user_auth"));
        dbInfoMap.put(JsonKey.ORG_DB, getDbInfoObject("sunbird","organisation"));
        dbInfoMap.put(JsonKey.PAGE_MGMT_DB, getDbInfoObject("sunbird","page_management"));
        dbInfoMap.put(JsonKey.PAGE_SECTION_DB, getDbInfoObject("sunbird","page_section"));
        dbInfoMap.put(JsonKey.SECTION_MGMT_DB, getDbInfoObject("sunbird","page_section"));
        dbInfoMap.put(JsonKey.ASSESSMENT_EVAL_DB, getDbInfoObject("sunbird","assessment_eval"));
        dbInfoMap.put(JsonKey.ASSESSMENT_ITEM_DB, getDbInfoObject("sunbird","assessment_item"));

        // EkStep HttpClient headers init
        headers.put("content-type", "application/json");
        headers.put("accept", "application/json");
        headers.put("user-id", "mahesh");
    }

    public static void checkCassandraDbConnections() {
    	if (readConfigFromEnv()) {
    		logger.info("db connection is created from System env variable.");
    		return ;
    	}   
        String[] ipList=prop.getProperty(JsonKey.DB_IP).split(",");
        String[] portList=prop.getProperty(JsonKey.DB_PORT).split(",");
        String[] keyspaceList=prop.getProperty(JsonKey.DB_KEYSPACE).split(",");

        String userName=prop.getProperty(JsonKey.DB_USERNAME);
        String password=prop.getProperty(JsonKey.DB_PASSWORD);
        for(int i=0;i<ipList.length;i++) {
            String ip = ipList[i];
            String port = portList[i];
            String keyspace = keyspaceList[i];

            try {

                boolean result = CassandraConnectionManager.createConnection(ip, port, userName,
                        password, keyspace);
                if (result) {
                    logger.info("CONNECTION CREATED SUCCESSFULLY FOR IP: " + ip + " : KEYSPACE :" + keyspace);
                    } else {
                    logger.info("CONNECTION CREATION FAILED FOR IP: " + ip + " : KEYSPACE :" + keyspace);
                    }

            } catch (ProjectCommonException ex) {
                logger.error(ex);
                }

        }

    }
    
    /**
     * This method will read the configuration from System variable.
     * @return boolean
     */
	public static boolean readConfigFromEnv() {
		boolean response = false;
		String ips = System.getenv(JsonKey.SUNBIRD_CASSANDRA_IP);
		if (ProjectUtil.isStringNullOREmpty(ips)) {
			logger.info("Configuration value is not coming form System variable.");
			return response;
		}
		String[] ipList = ips.split(",");
		String[] portList = System.getenv(JsonKey.SUNBIRD_CASSANDRA_PORT).split(",");
		String userName = System.getenv(JsonKey.SUNBIRD_CASSANDRA_USER_NAME);
		String password = System.getenv(JsonKey.SUNBIRD_CASSANDRA_PASSWORD);
		for (int i = 0; i < ipList.length; i++) {
			String ip = ipList[i];
			String port = portList[i];
			try {
				boolean result = CassandraConnectionManager.createConnection(ip, port, userName, password, KEY_SPACE_NAME);
				if (result) {
					logger.info("CONNECTION CREATED SUCCESSFULLY FOR IP: " + ip + " : KEYSPACE :" + KEY_SPACE_NAME);
				} else {
					logger.info("CONNECTION CREATION FAILED FOR IP: " + ip + " : KEYSPACE :" + KEY_SPACE_NAME);
				}
				response = true;
			} catch (ProjectCommonException ex) {
				logger.error(ex);
				throw new ProjectCommonException(ResponseCode.invaidConfiguration.getErrorCode(),
						ResponseCode.invaidConfiguration.getErrorCode(), ResponseCode.SERVER_ERROR.hashCode());
			}
		}
		return response;
	}
    
    
    private static void loadPropertiesFile() {

        InputStream input = null;

        try {
            input = Util.class.getClassLoader().getResourceAsStream("dbconfig.properties");
            // load a properties file
            prop.load(input);
        } catch (IOException ex) {
            ex.printStackTrace();
        } finally {
            if (input != null) {
                try {
                    input.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }

    }
    
    public static String getProperty(String key){
	      return prop.getProperty(key);
	   }

    /**
     * 
     * @param value
     * @return
     */
    private static DbInfo getDbInfoObject(String value) {

        DbInfo dbInfo = new DbInfo();

        String configDetails = prop.getProperty(value);
        String[] details = configDetails.split(",");

        dbInfo.setIp(details[0]);
        dbInfo.setPort(details[1]);
        dbInfo.setUserName(details[2]);
        dbInfo.setPassword(details[3]);
        dbInfo.setKeySpace(details[4]);
        dbInfo.setTableName(details[5]);

        return dbInfo;
    }

    private static DbInfo getDbInfoObject(String keySpace, String table) {

        DbInfo dbInfo = new DbInfo();

        dbInfo.setKeySpace(keySpace);
        dbInfo.setTableName(table);

        return dbInfo;
    }

    /**
     * class to hold cassandra db info.
     */
    public static class DbInfo {
        String keySpace;
        String tableName;
        String userName;
        String password;
        String ip;
        String port;

        /**
         * @param keySpace
         * @param tableName
         * @param userName
         * @param password
         */
        DbInfo(String keySpace, String tableName, String userName, String password, String ip, String port) {
            this.keySpace = keySpace;
            this.tableName = tableName;
            this.userName = userName;
            this.password = password;
            this.ip = ip;
            this.port = port;
        }

        /**
         * No-arg constructor
         */
        DbInfo() {
        }

        @Override
        public boolean equals(Object obj){
            if(obj instanceof DbInfo){
               DbInfo ob = (DbInfo)obj;
               if(this.ip.equals(ob.getIp()) && this.port.equals(ob.getPort()) &&
                       this.keySpace.equals(ob.getKeySpace())){
                   return true;
               }
            }
            return false;
        }

        @Override
        public int hashCode() {
            return 1;
        }

        public String getKeySpace() {
            return keySpace;
        }

        public void setKeySpace(String keySpace) {
            this.keySpace = keySpace;
        }

        public String getTableName() {
            return tableName;
        }

        public void setTableName(String tableName) {
            this.tableName = tableName;
        }

        public String getUserName() {
            return userName;
        }

        public void setUserName(String userName) {
            this.userName = userName;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public String getIp() {
            return ip;
        }

        public void setIp(String ip) {
            this.ip = ip;
        }

        public String getPort() {
            return port;
        }

        public void setPort(String port) {
            this.port = port;
        }
    }

    public static void removeAttributes(Map<String, Object> map ,List<String> keys ){

        if(null != map && null != keys) {
            for (String key : keys) {
                map.remove(key);
            }
        }
    }
    
    @SuppressWarnings("unchecked")
	public static SearchDTO createSearchDto(Map<String , Object> searchQueryMap){
    	SearchDTO search = new SearchDTO();
    	//Map<Object, Object> map = null;
    	if(searchQueryMap.containsKey(JsonKey.QUERY)){
    	 search.setQuery((String) searchQueryMap.get(JsonKey.QUERY));
    	}
    	if(searchQueryMap.containsKey(JsonKey.FACETS)){
    	 search.setFacets((List<String>) searchQueryMap.get(JsonKey.FACETS));
    	}
    	if(searchQueryMap.containsKey(JsonKey.FIELDS)){
    		search.setFields((List<String>) searchQueryMap.get(JsonKey.FIELDS));
    	}
    	if(searchQueryMap.containsKey(JsonKey.FILTERS)){
    		search.getAdditionalProperties().put(JsonKey.FILTERS,searchQueryMap.get(JsonKey.FILTERS));
    	}
    	if(searchQueryMap.containsKey(JsonKey.EXISTS)){
    		/*map = new HashMap<>();
    		for(String str : (List<String>)searchQueryMap.get(JsonKey.EXISTS) ){
    			map.put(str, 0);
    		}*/
    		search.getAdditionalProperties().put(JsonKey.EXISTS, searchQueryMap.get(JsonKey.EXISTS));
    	}
    	if(searchQueryMap.containsKey(JsonKey.NOT_EXISTS)){
    		/*map = new HashMap<>();
    		for(String str : (List<String>)searchQueryMap.get(JsonKey.NOT_EXISTS) ){
    			map.put(str, 0);
    		}*/
    		search.getAdditionalProperties().put(JsonKey.NOT_EXISTS, searchQueryMap.get(JsonKey.NOT_EXISTS));
    	}
    	if(searchQueryMap.containsKey(JsonKey.SORT_BY)){
    		//search.getSortBy().put(JsonKey.SORT_BY, searchQueryMap.get(JsonKey.SORT_BY));
    		search.getSortBy().putAll((Map<? extends String, ? extends String>) searchQueryMap.get(JsonKey.SORT_BY));
    	}
    	if(searchQueryMap.containsKey(JsonKey.OFFSET)){
    		if((searchQueryMap.get(JsonKey.OFFSET)) instanceof Integer ){
    			search.setOffset((int)searchQueryMap.get(JsonKey.OFFSET));
    		}else{
    			search.setOffset(((BigInteger) searchQueryMap.get(JsonKey.OFFSET)).intValue());
    		}
    	}
    	if(searchQueryMap.containsKey(JsonKey.LIMIT)){
    		if((searchQueryMap.get(JsonKey.LIMIT)) instanceof Integer ){
    			search.setLimit((int)searchQueryMap.get(JsonKey.LIMIT));
    		}else{
    		    search.setLimit(((BigInteger) searchQueryMap.get(JsonKey.LIMIT)).intValue());
    		}
    	}
    	if(searchQueryMap.containsKey(JsonKey.GROUP_QUERY)){
    	    search.getGroupQuery().addAll((Collection<? extends Map<String, Object>>) searchQueryMap.get(JsonKey.GROUP_QUERY));
        }
		return search;
    }

    public static void getContentData(Map<String, Object> section) {
        String response = "";
        JSONObject data;
        JSONObject jObject;
        ObjectMapper mapper = new ObjectMapper();
        try {
            response = HttpUtil.sendPostRequest(PropertiesCache.getInstance().getProperty(JsonKey.EKSTEP_CONTNET_SEARCH_URL),
                    (String) section.get(JsonKey.SEARCH_QUERY), headers);
            jObject = new JSONObject(response);
            data = jObject.getJSONObject(JsonKey.RESULT);
            JSONArray contentArray = data.getJSONArray(JsonKey.CONTENT);
            section.put(JsonKey.CONTENTS, mapper.readValue(contentArray.toString(), Object[].class));
        } catch (IOException e) {
            logger.error(e.getMessage(), e);
        } catch (JSONException e) {
            logger.error(e.getMessage(), e);
        }
    }
}