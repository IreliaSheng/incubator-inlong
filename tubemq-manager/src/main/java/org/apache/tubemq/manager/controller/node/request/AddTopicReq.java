/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.tubemq.manager.controller.node.request;


import lombok.Data;

@Data
public class AddTopicReq extends BaseReq {
    private String createUser;
    private String deleteWhen;
    private Integer unflushThreshold;
    private Boolean acceptPublish;
    private Integer numPartitions;
    private Integer unflushInterval;
    private Boolean acceptSubscribe;
    private String brokerId;
    private String confModAuthToken;
    private String topicName;
    private String deletePolicy;
}