package com.salesforce.cdp.queryservice.core;

import com.salesforce.cdp.queryservice.util.Constants;
import com.salesforce.cdp.queryservice.util.QueryExecutor;
import com.salesforce.cdp.queryservice.ResponseEnum;
import okhttp3.*;
import org.apache.http.HttpStatus;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.runners.MockitoJUnitRunner;

import java.io.IOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.*;

@RunWith(MockitoJUnitRunner.class)
public class QueryServiceStatementTest {

    @Rule
    public ExpectedException exceptionRule = ExpectedException.none();

    @Mock
    private QueryServiceConnection queryServiceConnection;

    @Mock
    private QueryExecutor queryExecutor;

    private QueryServiceStatement queryServiceStatement;

    @Before
    public void init() {
        doReturn(queryExecutor).when(queryServiceConnection).getQueryExecutor();
        queryServiceStatement = new QueryServiceStatement(queryServiceConnection, ResultSet.TYPE_FORWARD_ONLY,
                ResultSet.CONCUR_READ_ONLY);
    }

    @Test
    public void testExecuteQueryWithFailedResponse() throws SQLException, IOException {
        String jsonString = ResponseEnum.UNAUTHORIZED.getResponse();
        Response response = new Response.Builder().code(HttpStatus.SC_UNAUTHORIZED).
                request(buildRequest()).protocol(Protocol.HTTP_1_1).
                message("Unauthorized").
                body(ResponseBody.create(jsonString, MediaType.parse("application/json"))).build();
        doReturn(response).when(queryExecutor).executeQuery(anyString(), any(Optional.class), any(Optional.class), any(Optional.class));
        exceptionRule.expect(SQLException.class);
        exceptionRule.expectMessage("Authorization header verification failed");
        queryServiceStatement.executeQuery("select FirstName__c from Individual__dlm limit 10");
    }

    @Test
    public void testExecuteQueryWithSuccessfulResponse() throws IOException, SQLException {
        String jsonString = ResponseEnum.QUERY_RESPONSE.getResponse();
        Response response = new Response.Builder().code(HttpStatus.SC_OK).
                request(buildRequest()).protocol(Protocol.HTTP_1_1).
                message("Successful").
                body(ResponseBody.create(jsonString, MediaType.parse("application/json"))).build();
        doReturn(response).when(queryExecutor).executeQuery(anyString(), any(Optional.class), any(Optional.class), any(Optional.class));
        ResultSet resultSet = queryServiceStatement.executeQuery("select TelephoneNumber__c from ContactPointPhone__dlm GROUP BY 1");
        int count = 0;
        while (resultSet.next()) {
            Assert.assertNotNull(resultSet.getString(1));
            count++;
        }
        Assert.assertEquals(resultSet.getMetaData().getColumnCount(), 1);
        Assert.assertEquals(resultSet.getMetaData().getColumnName(1), "telephonenumber__c");
        Assert.assertEquals(resultSet.getMetaData().getColumnType(1), 12);
        Assert.assertEquals(count, 2);
    }

    @Test
    public void testExecuteQueryWithNoData() throws IOException, SQLException {
        String jsonString = ResponseEnum.EMPTY_RESPONSE.getResponse();
        Response response = new Response.Builder().code(HttpStatus.SC_OK).
                request(buildRequest()).protocol(Protocol.HTTP_1_1).
                message("Successful").
                body(ResponseBody.create(jsonString, MediaType.parse("application/json"))).build();
        doReturn(response).when(queryExecutor).executeQuery(anyString(), any(Optional.class), any(Optional.class), any(Optional.class));
        ResultSet resultSet = queryServiceStatement.executeQuery("select TelephoneNumber__c from ContactPointPhone__dlm GROUP BY 1");
        Assert.assertFalse(resultSet.next());
    }

    @Test
    public void testExceuteQueryWithIOException() throws IOException, SQLException {
        doThrow(new IOException("IO Exception")).when(queryExecutor).executeQuery(anyString(), any(Optional.class), any(Optional.class), any(Optional.class));
        exceptionRule.expect(SQLException.class);
        exceptionRule.expectMessage("IO Exception");
        queryServiceStatement.executeQuery("select FirstName__c from Individual__dlm limit 10");
    }

    @Test
    public void testPagination() throws IOException, SQLException {
        String paginationResponseString = ResponseEnum.PAGINATION_RESPONSE.getResponse();
        Response paginationResponse = new Response.Builder().code(HttpStatus.SC_OK).
                request(buildRequest()).protocol(Protocol.HTTP_1_1).
                message("Successful").
                body(ResponseBody.create(paginationResponseString, MediaType.parse("application/json"))).build();
        String queryResponseString = ResponseEnum.QUERY_RESPONSE.getResponse();
        Response queryResponse = new Response.Builder().code(HttpStatus.SC_OK).
                request(buildRequest()).protocol(Protocol.HTTP_1_1).
                message("Successful").
                body(ResponseBody.create(queryResponseString, MediaType.parse("application/json"))).build();
        doReturn(paginationResponse).doReturn(queryResponse).when(queryExecutor).executeQuery(anyString(), any(Optional.class), any(Optional.class), any(Optional.class));
        QueryServiceStatement queryServiceStatementSpy = Mockito.spy(queryServiceStatement);
        ResultSet resultSet = queryServiceStatementSpy.executeQuery("select TelephoneNumber__c from ContactPointPhone__dlm GROUP BY 1");
        int count = 0;
        while (resultSet.next()) {
            count++;
        }
        Assert.assertEquals(count, 4);
        verify(queryServiceStatementSpy, times(2)).executeQuery(eq("select TelephoneNumber__c from ContactPointPhone__dlm GROUP BY 1"));
    }

    @Test
    public void testDataWithMetadataResponse() throws IOException, SQLException {
        String jsonString = ResponseEnum.QUERY_RESPONSE_WITH_METADATA.getResponse();
        Response response = new Response.Builder().code(HttpStatus.SC_OK).
                request(buildRequest()).protocol(Protocol.HTTP_1_1).
                message("Successful").
                body(ResponseBody.create(jsonString, MediaType.parse("application/json"))).build();
        doReturn(response).when(queryExecutor).executeQuery(anyString(), any(Optional.class), any(Optional.class), any(Optional.class));
        ResultSet resultSet = queryServiceStatement.executeQuery("select TelephoneNumber__c from ContactPointPhone__dlm GROUP BY 1");
        int count = 0;
        while (resultSet.next()) {
            Assert.assertNotNull(resultSet.getString(1));
            count++;
        }
        Assert.assertEquals(resultSet.getMetaData().getColumnCount(), 1);
        Assert.assertEquals(resultSet.getMetaData().getColumnName(1), "count_num");
        Assert.assertEquals(resultSet.getMetaData().getColumnTypeName(1), "DECIMAL");
        Assert.assertEquals(count, 1);
    }

    @Test
    public void testQueryResponseWithoutPagination() throws IOException, SQLException {
        String paginationResponseString = ResponseEnum.QUERY_RESPONSE_WITHOUT_DONE_FLAG.getResponse();
        Response paginationResponse = new Response.Builder().code(HttpStatus.SC_OK).
                request(buildRequest()).protocol(Protocol.HTTP_1_1).
                message("Successful").
                body(ResponseBody.create(paginationResponseString, MediaType.parse("application/json"))).build();
        doReturn(paginationResponse).when(queryExecutor).executeQuery(anyString(), any(Optional.class), any(Optional.class), any(Optional.class));
        QueryServiceStatement queryServiceStatementSpy = Mockito.spy(queryServiceStatement);
        ResultSet resultSet = queryServiceStatementSpy.executeQuery("select TelephoneNumber__c from ContactPointPhone__dlm GROUP BY 1");
        int count = 0;
        while (resultSet.next()) {
            count++;
        }
        Assert.assertEquals(count, 2);
        verify(queryServiceStatementSpy, times(1)).executeQuery(eq("select TelephoneNumber__c from ContactPointPhone__dlm GROUP BY 1"));
    }


    private Request buildRequest() {
        return new Request.Builder()
                .url("https://mjrgg9bzgy2dsyzvmjrgkmzzg1.c360a.salesforce.com" + Constants.CDP_URL + Constants.ANSI_SQL_URL)
                .method(Constants.POST, RequestBody.create("{test: test}", MediaType.parse("application/json")))
                .build();
    }
}
