/*
 * Licensed to Elastic Search and Shay Banon under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. Elastic Search licenses this
 * file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.action.support.replication;

import org.elasticsearch.action.ActionRequestBuilder;
import org.elasticsearch.action.ActionResponse;
import org.elasticsearch.action.WriteConsistencyLevel;
import org.elasticsearch.client.internal.InternalGenericClient;
import org.elasticsearch.common.unit.TimeValue;

/**
 */
public abstract class IndicesReplicationOperationRequestBuilder<Request extends IndicesReplicationOperationRequest<Request>, Response extends ActionResponse, RequestBuilder extends IndicesReplicationOperationRequestBuilder<Request, Response, RequestBuilder>>
        extends ActionRequestBuilder<Request, Response, RequestBuilder> {

    protected IndicesReplicationOperationRequestBuilder(InternalGenericClient client, Request request) {
        super(client, request);
    }

    /**
     * A timeout to wait if the index operation can't be performed immediately. Defaults to <tt>1m</tt>.
     */
    @SuppressWarnings("unchecked")
    public final RequestBuilder setTimeout(TimeValue timeout) {
        request.setTimeout(timeout);
        return (RequestBuilder) this;
    }

    /**
     * A timeout to wait if the index operation can't be performed immediately. Defaults to <tt>1m</tt>.
     */
    @SuppressWarnings("unchecked")
    public final RequestBuilder setTimeout(String timeout) {
        request.setTimeout(timeout);
        return (RequestBuilder) this;
    }

    @SuppressWarnings("unchecked")
    public final RequestBuilder setIndices(String... indices) {
        request.setIndices(indices);
        return (RequestBuilder) this;
    }

    /**
     * Sets the replication type.
     */
    @SuppressWarnings("unchecked")
    public RequestBuilder setReplicationType(ReplicationType replicationType) {
        request.setReplicationType(replicationType);
        return (RequestBuilder) this;
    }

    /**
     * Sets the replication type.
     */
    @SuppressWarnings("unchecked")
    public RequestBuilder setReplicationType(String replicationType) {
        request.setReplicationType(replicationType);
        return (RequestBuilder) this;
    }

    /**
     * Sets the consistency level of write. Defaults to {@link org.elasticsearch.action.WriteConsistencyLevel#DEFAULT}
     */
    @SuppressWarnings("unchecked")
    public RequestBuilder setConsistencyLevel(WriteConsistencyLevel consistencyLevel) {
        request.setConsistencyLevel(consistencyLevel);
        return (RequestBuilder) this;
    }
}
