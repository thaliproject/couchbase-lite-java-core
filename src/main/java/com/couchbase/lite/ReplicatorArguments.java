package com.couchbase.lite;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.Principal;
import java.util.HashMap;
import java.util.Map;
import java.nio.charset.Charset;

import org.codehaus.jackson.JsonGenerationException;
import org.codehaus.jackson.map.JsonMappingException;
import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.type.TypeReference;


/**
 * Parses the arguments in a replication request so they can be seen and processed identically by both the
 * functional and security code.
 * This entire file exists because of https://github.com/couchbase/couchbase-lite-java-core/issues/43
 */
public class ReplicatorArguments {
    public static final String sourceFieldName = "source";
    public static final String targetFieldName = "target";
    public static final String urlFieldName = "url";
    public static final String authFieldName = "auth";
    public static final String queryParamsFieldName = "query_params";
    public static final String createTargetFieldName = "create_target";
    public static final String continuousFieldName = "continuous";
    public static final String cancelFieldName = "cancel";
    public static final String filterFieldName = "filter";

    private final Map<String, Object> rawProperties;
    private final Map<String, Object> queryParams;
    private final Map<String, Object> headers;
    private final Map<String, Object> authMap;
    private String source;
    private String target;
    private final Map<String, Object> sourceAuth;
    private final Map<String, Object> targetAuth;
    private final boolean createTarget;
    private final boolean continuous;
    private final boolean cancel;
    private final boolean push;
    private final String filterName;
    private final Principal principal;

    // https://github.com/thaliproject/thali/issues/58
    // is this request to be handled by the replication manager?
    private final boolean managedReplication;
    public static final String managedReplicationFieldName = "managed_replication";

    public ReplicatorArguments(Map<String, Object> properties, Manager manager, Principal principal) throws CouchbaseLiteException {
        // Start taken from manager.java
        Map<String, Object> sourceMap = parseSourceOrTarget(properties, sourceFieldName);
        Map<String, Object> targetMap = parseSourceOrTarget(properties, targetFieldName);

        source = (String)sourceMap.get(urlFieldName);
        target = (String)targetMap.get(urlFieldName);

        Boolean createTargetBoolean = (Boolean)properties.get(createTargetFieldName);
        createTarget = (createTargetBoolean != null && createTargetBoolean);

        Boolean continuousBoolean = (Boolean)properties.get(continuousFieldName);
        continuous = (continuousBoolean != null && continuousBoolean);

        Boolean cancelBoolean = (Boolean)properties.get(cancelFieldName);
        cancel = (cancelBoolean != null && cancelBoolean);

        // Map the 'source' and 'target' JSON params to a local database and remote URL:
        if(source == null || target == null) {
            throw new CouchbaseLiteException("source and target are both null", new Status(Status.BAD_REQUEST));
        }

        if (Manager.isValidDatabaseName(getSource()) == false && Manager.isValidDatabaseName(getTarget()) == false) {
            throw new CouchbaseLiteException("source and target are both not valid local database names, we don't support having CouchBase Lite act as the middle man between replications of two database it doesn't own.",
                    new Status(Status.BAD_REQUEST));
        }

        push =  Manager.isValidDatabaseName(getSource());

        authMap = push ? targetMap : sourceMap;

        filterName = (String)properties.get(filterFieldName);
        // End taken from manager.java

        // NOTE: https://github.com/couchbase/couchbase-lite-java-core/issues/42 is implemented by removing
        // https://github.com/couchbase/couchbase-lite-java-core/blob/master/src/main/java/com/couchbase/lite/Manager.java#L572

        rawProperties = properties;
        queryParams = (Map<String,Object>)properties.get(queryParamsFieldName);
        headers = (Map<String, Object>)properties.get("headers");
        sourceAuth = (Map<String, Object>) sourceMap.get(authFieldName);
        targetAuth = (Map<String, Object>) targetMap.get(authFieldName);
        this.principal = principal;

        // is this a managed replication?
        Boolean managedReplicationBoolean = (Boolean)properties.get(managedReplicationFieldName);
        managedReplication = (managedReplicationBoolean != null && managedReplicationBoolean);
    }

    public Map<String, Object> getRawProperties() { return getRawProperties(true); }

    public Map<String, Object> getQueryParams() {
        return queryParams;
    }

    public Map<String, Object> getHeaders() { return headers; }

    public String getSource() {
        return source;
    }

    public void setSource(String source) { this.source = source; }

    public String getTarget() {
        return target;
    }

    public void setTarget(String target) { this.target = target; }

    public boolean getCreateTarget() {
        return createTarget;
    }

    public boolean getContinuous() {
        return continuous;
    }

    public boolean getCancel() {
        return cancel;
    }

    public Map<String, Object> getSourceAuth() {
        return sourceAuth;
    }

    public Map<String, Object> getTargetAuth() {
        return targetAuth;
    }

    public Map<String, Object> getAuthMap() { return authMap; }

    /**
     * If true then data from a local database (source) is being pushed to a remote database (target). If false then data from
     * a remote database (source) is being copied to a local database (target).
     * @return
     */
    public boolean getPush() { return push; }

    public String getFilterName() { return filterName; }

    public Principal getPrincipal() { return principal; }

    // Taken directly from manager.java so we have to constantly check back there to see if anything changed
    private Map<String, Object> parseSourceOrTarget(Map<String,Object> properties, String key) {
        Map<String, Object> result = new HashMap<String, Object>();

        Object value = properties.get(key);

        if (value instanceof String) {
            result.put(urlFieldName, value);
        }
        else if (value instanceof Map) {
            result = (Map<String, Object>) value;
        }
        return result;
    }

    // allow for the filtering of the "managed replication" requests
    public boolean getManagedReplication() { return managedReplication; }

    public Map<String, Object> getRawProperties(boolean removeManaged) {
        if((!removeManaged) || (!rawProperties.containsKey(managedReplicationFieldName))) {
            return rawProperties;
        }
        Map<String, Object> propertyMap = new HashMap<String, Object>(rawProperties);
        propertyMap.remove(managedReplicationFieldName);
        return propertyMap;
    }
}
