/*
 * Copyright (c) 2021, salesforce.com, inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *          http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.salesforce.cdp.queryservice.core;

import com.google.common.annotations.VisibleForTesting;
import com.salesforce.cdp.queryservice.auth.token.OffcoreToken;
import com.salesforce.cdp.queryservice.enums.QueryEngineEnum;
import com.salesforce.cdp.queryservice.model.QueryConfigResponse;
import com.salesforce.cdp.queryservice.util.Constants;
import com.salesforce.cdp.queryservice.util.HttpHelper;
import com.salesforce.cdp.queryservice.util.QueryExecutor;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Response;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.sql.*;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.salesforce.cdp.queryservice.util.Messages.QUERY_CONFIG_ERROR;

@Slf4j
public class QueryServiceConnection implements Connection {

    private AtomicBoolean closed = new AtomicBoolean(false);
    private Properties properties;
    private final String serviceRootUrl;
    private OffcoreToken token;
    private boolean enableArrowStream = false;
    private boolean isCursorBasedPaginationReq = true;
    private final boolean isSocksProxyDisabled;
    private boolean enableStreamFlow = false;
    private String tenantUrl;
    private QueryEngineEnum queryEngineEnum;

    private boolean isValid = false;

    public QueryServiceConnection(String url, Properties properties) throws SQLException {
        this.properties = properties; // fixme: do deepCopy and modify the props
        this.serviceRootUrl = getServiceRootUrl(url);
        this.properties.put(Constants.LOGIN_URL, serviceRootUrl);
        addClientUsernameIfRequired(this.properties);

        // default `enableArrowStream` is false
        enableArrowStream = Boolean.parseBoolean(this.properties.getProperty(Constants.ENABLE_ARROW_STREAM));

        // default `isCursorBasedPaginationReq` is true
        isCursorBasedPaginationReq = Boolean.parseBoolean(this.properties.getProperty(Constants.CURSOR_BASED_PAGINATION, Constants.TRUE_STR));

        this.isSocksProxyDisabled = Boolean.parseBoolean(this.properties.getProperty(Constants.DISABLE_SOCKS_PROXY));

        boolean isTableauConnection = Constants.TABLEAU_USER_AGENT_VALUE.equals(properties.getProperty(Constants.USER_AGENT));

        // default `enableStreamFlow` is false
        enableStreamFlow = isTableauConnection || Boolean.parseBoolean(this.properties.getProperty(Constants.ENABLE_STREAM_FLOW, Constants.FALSE_STR));

        log.info("isTableauConnection {}, enableStreamFlow {}", isTableauConnection, enableStreamFlow);

        // use isValid to test connection
        this.isValid(20);
    }

    /**
     * Returns the extracted service url from given jdbc endpoint
     *
     * @param url jdbc url which contains service url
     * @return service url
     * @throws SQLException when given url doesn't belong with required datasource
     */
    @VisibleForTesting
    static String getServiceRootUrl(String url) throws SQLException {
        if (url == null) {
            throw new SQLException("url is null");
        }
        if (!url.startsWith(Constants.DATASOURCE_TYPE)) {
            throw new SQLException("url is specified with invalid datasource");
        }
        String serviceRootUrl = url.substring(Constants.DATASOURCE_TYPE.length());
        // removes ending slash if present
        return StringUtils.removeEnd(serviceRootUrl, "/");
    }

    /**
     * Adds client secrets to properties if not present and service url matches one of the existing envs.
     *
     * @param properties Properties containing the config
     * @throws SQLException when given service url doesn't match any envs and config doesn't have exists secrets
     */
    @VisibleForTesting
    static void addClientUsernameIfRequired(Properties properties) throws SQLException {
        if (properties.containsKey(Constants.USER) && !properties.containsKey(Constants.USER_NAME)) {
            properties.put(Constants.USER_NAME, properties.get(Constants.USER));
        }
    }

    public boolean getEnableArrowStream() {
        return this.enableArrowStream;
    }

    public boolean isCursorBasedPaginationReq() {
        return this.isCursorBasedPaginationReq;
    }

    public boolean isSocksProxyDisabled() {
        return this.isSocksProxyDisabled;
    }

    public boolean isEnableStreamFlow() {
        return enableStreamFlow;
    }

    public boolean updateStreamFlow(boolean flag) {
        enableStreamFlow = flag;
        return enableStreamFlow;
    }

    public QueryEngineEnum getQueryEngineEnum() {
        return queryEngineEnum;
    }

    @Override
    public Statement createStatement() throws SQLException {
        return createStatement(ResultSet.TYPE_FORWARD_ONLY,
                ResultSet.CONCUR_READ_ONLY);
    }

    @Override
    public PreparedStatement prepareStatement(String sql) throws SQLException {
       throw new SQLFeatureNotSupportedException("PreparedStatement is not supported");
    }

    @Override
    public CallableStatement prepareCall(String sql) throws SQLException {
        throw new SQLException("Callable Statements are not supported in query service");
    }

    @Override
    public String nativeSQL(String sql) throws SQLException {
        throw new SQLException("Only ANSI SQL queries are supported");
    }

    @Override
    public void setAutoCommit(boolean autoCommit) throws SQLException {
        //NOOP as there is no updates happening from query service.
    }

    @Override
    public boolean getAutoCommit() throws SQLException {
        return false;
    }

    @Override
    public void commit() throws SQLException {
        //NOOP as there is no updates happening from query service.
    }

    @Override
    public void rollback() throws SQLException {
        //NOOP as there is no updates happening from query service.
    }

    @Override
    public void close() throws SQLException {
        cleanup();
        closed.set(true);
    }

    @Override
    public boolean isClosed() throws SQLException {
        return closed.get();
    }

    @Override
    public DatabaseMetaData getMetaData() {
        return new QueryServiceMetadata(this, serviceRootUrl, properties);
    }

    @Override
    public void setReadOnly(boolean readOnly) throws SQLException {
        //NOOP as the service is RO.
    }

    @Override
    public boolean isReadOnly() throws SQLException {
        return true;
    }

    @Override
    public void setCatalog(String catalog) throws SQLException {
        //NOOP
    }

    @Override
    public String getCatalog() throws SQLException {
        return StringUtils.EMPTY;
    }

    @Override
    public void setTransactionIsolation(int level) throws SQLException {
        //NOOP
    }

    @Override
    public int getTransactionIsolation() throws SQLException {
        return Connection.TRANSACTION_NONE;
    }

    @Override
    public SQLWarning getWarnings() throws SQLException {
        return null;
    }

    @Override
    public void clearWarnings() throws SQLException {
        //NOOP
    }

    @Override
    public Statement createStatement(int resultSetType, int resultSetConcurrency) throws SQLException {
        return new QueryServiceStatement(this, resultSetType, resultSetConcurrency);
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency) throws SQLException {
        throw new SQLFeatureNotSupportedException("PreparedStatement is not supported");
    }

    @Override
    public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency) throws SQLException {
        throw new SQLFeatureNotSupportedException("Calling stored procedures are not supported");
    }

    @Override
    public Map<String, Class<?>> getTypeMap() throws SQLException {
        return null;
    }

    @Override
    public void setTypeMap(Map<String, Class<?>> map) throws SQLException {
        //NOOP
    }

    @Override
    public void setHoldability(int holdability) throws SQLException {
        //NOOP
    }

    @Override
    public int getHoldability() throws SQLException {
        return 0;
    }

    @Override
    public Savepoint setSavepoint() throws SQLException {
        return null;
    }

    @Override
    public Savepoint setSavepoint(String name) throws SQLException {
        return null;
    }

    @Override
    public void rollback(Savepoint savepoint) throws SQLException {
        //NOOP
    }

    @Override
    public void releaseSavepoint(Savepoint savepoint) throws SQLException {
        //NOOP
    }

    @Override
    public Statement createStatement(int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException {
        throw new SQLFeatureNotSupportedException("Setting holdability is not supported");
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException {
        throw new SQLFeatureNotSupportedException("Setting holdability is not supported");
    }

    @Override
    public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException {
        throw new SQLFeatureNotSupportedException("Calling stored procedures are not supported");
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int autoGeneratedKeys) throws SQLException {
        throw new SQLFeatureNotSupportedException("Autogenerated keys not supported");
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int[] columnIndexes) throws SQLException {
        throw new SQLFeatureNotSupportedException("column Indexes not supported");
    }

    @Override
    public PreparedStatement prepareStatement(String sql, String[] columnNames) throws SQLException {
        throw new SQLFeatureNotSupportedException("column Name not supported");
    }

    @Override
    public Clob createClob() throws SQLException {
        return null;
    }

    @Override
    public Blob createBlob() throws SQLException {
        return null;
    }

    @Override
    public NClob createNClob() throws SQLException {
        return null;
    }

    @Override
    public SQLXML createSQLXML() throws SQLException {
        return null;
    }

    @Override
    public boolean isValid(int timeout) throws SQLException {
        if (isClosed()) {
            return false;
        }

        try {
            if(this.isValid) {
                log.info("Reusing connection");
                return true;
            }

            QueryConfigResponse configResponse = getQueryConfigResponse();
            this.queryEngineEnum = QueryEngineEnum.TRINO;
            if(configResponse.getQueryengine() != null) {
                QueryEngineEnum.fromValue(configResponse.getQueryengine());
            }
            this.isValid = true;
            return true;
        } catch (Exception e) {
            log.error("Exception while connecting to server", e);
            throw e;
        }
    }

    @Override
    public void setClientInfo(String name, String value) throws SQLClientInfoException {
        cleanup();
        properties.put(name, value);
    }

    @Override
    public void setClientInfo(Properties properties) throws SQLClientInfoException {
        cleanup();
        this.properties = properties;
    }

    @Override
    public String getClientInfo(String name) throws SQLException {
        return properties.getProperty(name);
    }

    @Override
    public Properties getClientInfo() throws SQLException {
        return properties;
    }

    @Override
    public Array createArrayOf(String typeName, Object[] elements) throws SQLException {
        return null;
    }

    @Override
    public Struct createStruct(String typeName, Object[] attributes) throws SQLException {
        return null;
    }

    @Override
    public void setSchema(String schema) throws SQLException {
        //NOOP
    }

    @Override
    public String getSchema() throws SQLException {
        return StringUtils.EMPTY;
    }

    @Override
    public void abort(Executor executor) throws SQLException {
        //NOOP
    }

    @Override
    public void setNetworkTimeout(Executor executor, int milliseconds) throws SQLException {
        // TODO: Check if any timeout is needed for client if we can incorporate that to http client.
    }

    @Override
    public int getNetworkTimeout() throws SQLException {
        return 0;
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        return null;
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return false;
    }

    private void cleanup() {
        // todo: shoudn't cleanup also clear/reset properties?
        token = null;
    }

    public void setToken(OffcoreToken token) {
        // Store token at connection level only for username password and key pair auth flows.
        if ((properties.containsKey(Constants.USER_NAME) && properties.containsKey(Constants.PD)) ||
                (properties.containsKey(Constants.USER_NAME) && properties.containsKey(Constants.PRIVATE_KEY))) {
            this.token = token;
        }
    }

    public OffcoreToken getToken() {
        return token;
    }

    public String getTenantUrl() {
        return tenantUrl;
    }

    public void setTenantUrl(String tenantUrl) {
        this.tenantUrl = tenantUrl;
    }

    private QueryExecutor createQueryExecutor() {
        return new QueryExecutor(this);
    }

    QueryConfigResponse getQueryConfigResponse() throws SQLException {
        try {
            QueryExecutor executor = createQueryExecutor();
            Response response = executor.getQueryConfig();

            return HttpHelper.handleSuccessResponse(response, QueryConfigResponse.class);
        } catch (IOException e) {
            log.error("Exception while getting config from query service", e);
            throw new SQLException(QUERY_CONFIG_ERROR, e);
        }
    }

    public CharSequence getDataspace() {
        return properties.getProperty(Constants.DATASPACE);
    }

    public void setDataspace(String dataspace) {
        properties.put(Constants.DATASPACE,dataspace);
    }
}