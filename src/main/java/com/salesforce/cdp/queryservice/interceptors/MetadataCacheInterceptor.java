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

package com.salesforce.cdp.queryservice.interceptors;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.salesforce.cdp.queryservice.core.QueryServiceConnection;
import com.salesforce.cdp.queryservice.model.MetadataCacheKey;
import com.salesforce.cdp.queryservice.util.Constants;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.apache.http.HttpStatus;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

@Slf4j
public class MetadataCacheInterceptor implements Interceptor {
    private final QueryServiceConnection connection;
    private Cache<MetadataCacheKey, String> metaDataCache;

    public MetadataCacheInterceptor(QueryServiceConnection connection) {
        this.connection = connection;
        this.metaDataCache = CacheBuilder.newBuilder()
                .expireAfterWrite(connection.getMetaDataCacheDurationInMs(), TimeUnit.MILLISECONDS)
                .maximumSize(10).build();
    }

    @NotNull
    @Override
    public Response intercept(@NotNull Chain chain) throws IOException {
        Request request = chain.request();
        Response response;
        String responseString = getMetadataFromCacheIfPresent();

        Response.Builder responseBuilder = new Response.Builder().code(HttpStatus.SC_OK).
                request(request).protocol(Protocol.HTTP_1_1).
                message("OK");

        if (responseString != null) {
            log.trace("Getting the metadata response from local cache");
            responseBuilder.addHeader("from-local-cache", Constants.TRUE_STR);
        } else {
            log.trace("Cache miss for metadata response. Getting from server");
            response = chain.proceed(request);

            if(!response.isSuccessful()) {
                return response;
            } else {
                log.info("Caching the response");
                responseString = response.body().string();
                cacheMetadata(responseString);
            }
        }

        responseBuilder.body(ResponseBody.create(responseString, MediaType.parse(Constants.JSON_CONTENT)));
        return responseBuilder.build();
    }

    private void cacheMetadata(String response) {
        MetadataCacheKey cacheKey = connection.getMetadataCacheKey();
        metaDataCache.put(cacheKey, response);
    }

    public String getMetadataFromCacheIfPresent() {
        MetadataCacheKey cacheKey = connection.getMetadataCacheKey();
        return metaDataCache.getIfPresent(cacheKey);
    }
}
