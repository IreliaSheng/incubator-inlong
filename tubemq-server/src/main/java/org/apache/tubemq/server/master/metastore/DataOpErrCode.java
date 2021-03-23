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

package org.apache.tubemq.server.master.metastore;


public enum DataOpErrCode {
    DERR_SUCCESS(200, "Success."),
    DERR_NOT_EXIST(401, "Record not exist."),
    DERR_EXISTED(402, "Record has existed."),
    DERR_UNCHANGED(403, "Record not changed."),
    DERR_STORE_ABNORMAL(501, "Store layer throw exception."),

    STATUS_DISABLE(0, "Disable.");

    private int code;
    private String description;


    DataOpErrCode(int code, String description) {
        this.code = code;
        this.description = description;
    }

    public int getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

    public static DataOpErrCode valueOf(int code) {
        for (DataOpErrCode status : DataOpErrCode.values()) {
            if (status.getCode() == code) {
                return status;
            }
        }
        throw new IllegalArgumentException(String.format("unknown data operate error code %s", code));
    }

}
