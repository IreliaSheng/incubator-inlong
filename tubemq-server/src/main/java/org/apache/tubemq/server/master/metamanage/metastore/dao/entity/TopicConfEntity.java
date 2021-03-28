/**
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

package org.apache.tubemq.server.master.metamanage.metastore.dao.entity;

import java.util.Date;
import java.util.Objects;
import org.apache.tubemq.corebase.TBaseConstants;
import org.apache.tubemq.corebase.utils.KeyBuilderUtils;
import org.apache.tubemq.corebase.utils.TStringUtils;
import org.apache.tubemq.server.common.statusdef.TopicStatus;
import org.apache.tubemq.server.master.bdbstore.bdbentitys.BdbTopicConfEntity;


/*
 * store the topic configure setting
 *
 */
public class TopicConfEntity extends BaseEntity {

    private String recordKey = "";
    private String topicName = "";
    private int brokerId = TBaseConstants.META_VALUE_UNDEFINED;
    private String brokerIp =  "";
    private int brokerPort = TBaseConstants.META_VALUE_UNDEFINED;
    private String brokerAddress = "";
    // topic id, require globally unique
    private int topicId = TBaseConstants.META_VALUE_UNDEFINED;
    private TopicStatus deployStatus = TopicStatus.STATUS_TOPIC_UNDEFINED;  // topic status
    private TopicPropGroup topicProps = null;


    public TopicConfEntity() {
        super();
    }

    public TopicConfEntity(String topicName, int topicId, int brokerId,
                           String brokerIp, int brokerPort,
                           TopicPropGroup topicProps, TopicStatus deployStatus,
                           long dataVersionId, String createUser,
                           Date createDate, String modifyUser, Date modifyDate) {
        super(dataVersionId, createUser, createDate, modifyUser, modifyDate);
        setTopicDeployInfo(brokerId, brokerIp, brokerPort, topicName, topicId);
        this.deployStatus = deployStatus;
        this.topicProps = topicProps;
    }

    public TopicConfEntity(BdbTopicConfEntity bdbEntity) {
        super(bdbEntity.getDataVerId(),
                bdbEntity.getCreateUser(), bdbEntity.getCreateDate(),
                bdbEntity.getModifyUser(), bdbEntity.getModifyDate());
        setTopicDeployInfo(bdbEntity.getBrokerId(),
                bdbEntity.getBrokerIp(), bdbEntity.getBrokerPort(),
                bdbEntity.getTopicName(), bdbEntity.getTopicId());
        this.deployStatus = TopicStatus.valueOf(bdbEntity.getTopicStatusId());
        this.topicProps =
                new TopicPropGroup(bdbEntity.getNumTopicStores(), bdbEntity.getNumPartitions(),
                        bdbEntity.getUnflushThreshold(), bdbEntity.getUnflushInterval(),
                        bdbEntity.getUnflushDataHold(), bdbEntity.getMemCacheMsgSizeInMB(),
                        bdbEntity.getMemCacheMsgCntInK(), bdbEntity.getMemCacheFlushIntvl(),
                        bdbEntity.getAcceptPublish(), bdbEntity.getAcceptSubscribe(),
                        bdbEntity.getDeletePolicy(), bdbEntity.getDataStoreType(),
                        bdbEntity.getDataPath());
        this.setAttributes(bdbEntity.getAttributes());
    }

    public BdbTopicConfEntity buildBdbTopicConfEntity() {
        BdbTopicConfEntity bdbEntity =
                new BdbTopicConfEntity(brokerId, brokerIp, brokerPort, topicName,
                        topicProps.getNumPartitions(), topicProps.getUnflushThreshold(),
                        topicProps.getUnflushInterval(), "",
                        topicProps.getDeletePolicy(), topicProps.isAcceptPublish(),
                        topicProps.isAcceptSubscribe(), topicProps.getNumTopicStores(),
                        getAttributes(), getCreateUser(), getCreateDate(),
                        getModifyUser(), getModifyDate());
        bdbEntity.setDataVerId(getDataVersionId());
        bdbEntity.setTopicId(topicId);
        bdbEntity.setNumTopicStores(topicProps.getNumTopicStores());
        bdbEntity.setMemCacheMsgSizeInMB(topicProps.getMemCacheMsgSizeInMB());
        bdbEntity.setMemCacheMsgCntInK(topicProps.getMemCacheMsgCntInK());
        bdbEntity.setMemCacheFlushIntvl(topicProps.getMemCacheFlushIntvl());
        bdbEntity.setUnflushDataHold(topicProps.getUnflushDataHold());
        return bdbEntity;
    }

    public void setTopicDeployInfo(int brokerId, String brokerIp, int brokerPort,
                                   String topicName, int topicId) {
        this.brokerId = brokerId;
        this.brokerIp = brokerIp;
        this.brokerPort = brokerPort;
        this.topicName = topicName;
        this.topicId = topicId;
        this.recordKey = KeyBuilderUtils.buildTopicConfRecKey(brokerId, topicName);
        this.brokerAddress = KeyBuilderUtils.buildAddressInfo(brokerIp, brokerPort);
    }

    public String getRecordKey() {
        return recordKey;
    }

    public int getBrokerId() {
        return brokerId;
    }

    public String getBrokerIp() {
        return brokerIp;
    }

    public int getBrokerPort() {
        return brokerPort;
    }

    public String getBrokerAddress() {
        return brokerAddress;
    }

    public TopicPropGroup getTopicProps() {
        return topicProps;
    }

    public void setTopicProps(TopicPropGroup topicProps) {
        this.topicProps = topicProps;
    }

    public int getTopicId() {
        return topicId;
    }

    public void setTopicId(int topicId) {
        this.topicId = topicId;
    }

    public String getTopicName() {
        return topicName;
    }

    public TopicStatus getTopicStatus() {
        return deployStatus;
    }

    public void setTopicStatusId(int topicStatusId) {
        this.deployStatus = TopicStatus.valueOf(topicStatusId);
    }

    public boolean isInRemoving() {
        return (this.deployStatus == TopicStatus.STATUS_TOPIC_SOFT_REMOVE
                || this.deployStatus == TopicStatus.STATUS_TOPIC_HARD_REMOVE);
    }

    public int getTopicStatusId() {
        return deployStatus.getCode();
    }

    public boolean isValidTopicStatus() {
        return this.deployStatus == TopicStatus.STATUS_TOPIC_OK;
    }

    /**
     * Check whether the specified query item value matches
     * Allowed query items:
     *   brokerId, topicId, topicName, topicStatus
     * @return true: matched, false: not match
     */
    public boolean isMatched(TopicConfEntity target) {
        if (target == null) {
            return true;
        }
        if (!super.isMatched(target)) {
            return false;
        }
        if ((target.getBrokerId() != TBaseConstants.META_VALUE_UNDEFINED
                && target.getBrokerId() != this.brokerId)
                || (target.getTopicId() != TBaseConstants.META_VALUE_UNDEFINED
                && target.getTopicId() != this.topicId)
                || (TStringUtils.isNotBlank(target.getTopicName())
                && !target.getTopicName().equals(this.topicName))
                || (TStringUtils.isNotBlank(target.getBrokerIp())
                && !target.getBrokerIp().equals(this.brokerIp))
                || (target.getTopicStatus() != TopicStatus.STATUS_TOPIC_UNDEFINED
                && target.getTopicStatus() != this.deployStatus)) {
            return false;
        }
        return true;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TopicConfEntity)) {
            return false;
        }
        if (!super.equals(o)) {
            return false;
        }
        TopicConfEntity that = (TopicConfEntity) o;
        return brokerId == that.brokerId &&
                brokerPort == that.brokerPort &&
                topicId == that.topicId &&
                recordKey.equals(that.recordKey) &&
                topicName.equals(that.topicName) &&
                Objects.equals(brokerIp, that.brokerIp) &&
                Objects.equals(brokerAddress, that.brokerAddress) &&
                deployStatus == that.deployStatus &&
                Objects.equals(topicProps, that.topicProps);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), recordKey, topicName, brokerId,
                brokerIp, brokerPort, brokerAddress, topicId, deployStatus, topicProps);
    }
}