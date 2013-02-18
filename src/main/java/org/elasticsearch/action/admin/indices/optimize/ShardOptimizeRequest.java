/*
 * Licensed to ElasticSearch and Shay Banon under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. ElasticSearch licenses this
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

package org.elasticsearch.action.admin.indices.optimize;

import org.elasticsearch.action.support.broadcast.BroadcastShardOperationRequest;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;

import java.io.IOException;

/**
 *
 */
class ShardOptimizeRequest extends BroadcastShardOperationRequest {

    private boolean waitForMerge = OptimizeRequest.Defaults.WAIT_FOR_MERGE;
    private int maxNumSegments = OptimizeRequest.Defaults.MAX_NUM_SEGMENTS;
    private boolean onlyExpungeDeletes = OptimizeRequest.Defaults.ONLY_EXPUNGE_DELETES;
    private boolean flush = OptimizeRequest.Defaults.FLUSH;
    private boolean refresh = OptimizeRequest.Defaults.REFRESH;

    ShardOptimizeRequest() {
    }

    public ShardOptimizeRequest(String index, int shardId, OptimizeRequest request) {
        super(index, shardId, request);
        waitForMerge = request.isWaitForMerge();
        maxNumSegments = request.getMaxNumSegments();
        onlyExpungeDeletes = request.isOnlyExpungeDeletes();
        flush = request.isFlush();
        refresh = request.isRefresh();
    }

    public boolean isWaitForMerge() {
        return waitForMerge;
    }

    public int getMaxNumSegments() {
        return maxNumSegments;
    }

    public boolean isOnlyExpungeDeletes() {
        return onlyExpungeDeletes;
    }

    public boolean isFlush() {
        return flush;
    }

    public boolean isRefresh() {
        return refresh;
    }

    @Override
    public void readFrom(StreamInput in) throws IOException {
        super.readFrom(in);
        waitForMerge = in.readBoolean();
        maxNumSegments = in.readInt();
        onlyExpungeDeletes = in.readBoolean();
        flush = in.readBoolean();
        refresh = in.readBoolean();
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        super.writeTo(out);
        out.writeBoolean(waitForMerge);
        out.writeInt(maxNumSegments);
        out.writeBoolean(onlyExpungeDeletes);
        out.writeBoolean(flush);
        out.writeBoolean(refresh);
    }
}