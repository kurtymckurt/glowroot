/*
 * Copyright 2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.glowroot.config;

import java.util.Map;

import javax.annotation.Nullable;

import com.google.common.base.Charsets;
import com.google.common.hash.Hashing;
import org.immutables.value.Json;
import org.immutables.value.Value;

import org.glowroot.common.Marshaling2;

@Value.Immutable
@Json.Marshaled
public abstract class SmtpConfig {

    @Value.Default
    public String fromEmailAddress() {
        return "";
    }
    @Value.Default
    public String fromDisplayName() {
        return "";
    }
    @Value.Default
    public String host() {
        return "";
    }
    public abstract @Nullable Integer port();
    @Value.Default
    public boolean ssl() {
        return false;
    }
    @Value.Default
    public String username() {
        return "";
    }
    @Value.Default
    public String encryptedPassword() {
        return "";
    }
    public abstract Map<String, String> additionalProperties();

    @Value.Derived
    @Json.Ignore
    public String version() {
        return Hashing.sha1().hashString(Marshaling2.toJson(this), Charsets.UTF_8).toString();
    }
}