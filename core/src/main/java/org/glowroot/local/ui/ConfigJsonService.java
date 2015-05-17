/*
 * Copyright 2011-2015 the original author or authors.
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
package org.glowroot.local.ui;

import java.net.InetAddress;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;
import javax.mail.Message;
import javax.mail.Session;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.RequiresNonNull;
import org.immutables.value.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.common.Encryption;
import org.glowroot.common.ObjectMappers;
import org.glowroot.config.AdvancedConfig;
import org.glowroot.config.AnonymousAccess;
import org.glowroot.config.ConfigService;
import org.glowroot.config.ConfigService.OptimisticLockException;
import org.glowroot.config.GeneralConfig;
import org.glowroot.config.PluginConfig;
import org.glowroot.config.PluginDescriptor;
import org.glowroot.config.PropertyDescriptor;
import org.glowroot.config.PropertyValue;
import org.glowroot.config.SmtpConfig;
import org.glowroot.config.StorageConfig;
import org.glowroot.config.UserInterfaceConfig;
import org.glowroot.config.UserRecordingConfig;
import org.glowroot.local.store.AlertingService;
import org.glowroot.local.store.CappedDatabase;
import org.glowroot.local.store.MailService;
import org.glowroot.local.ui.HttpServer.PortChangeFailedException;
import org.glowroot.transaction.TransactionModule;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static io.netty.handler.codec.http.HttpResponseStatus.PRECONDITION_FAILED;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

@JsonService
class ConfigJsonService {

    private static final Logger logger = LoggerFactory.getLogger(ConfigJsonService.class);
    private static final ObjectMapper mapper = ObjectMappers.create();

    private final ConfigService configService;
    private final CappedDatabase cappedDatabase;
    private final ImmutableList<PluginDescriptor> pluginDescriptors;
    private final HttpSessionManager httpSessionManager;
    private final TransactionModule transactionModule;
    private final MailService mailService;

    private volatile @MonotonicNonNull HttpServer httpServer;

    ConfigJsonService(ConfigService configService, CappedDatabase cappedDatabase,
            List<PluginDescriptor> pluginDescriptors, HttpSessionManager httpSessionManager,
            TransactionModule transactionModule, MailService mailService) {
        this.configService = configService;
        this.cappedDatabase = cappedDatabase;
        this.pluginDescriptors = ImmutableList.copyOf(pluginDescriptors);
        this.httpSessionManager = httpSessionManager;
        this.transactionModule = transactionModule;
        this.mailService = mailService;
    }

    void setHttpServer(HttpServer httpServer) {
        this.httpServer = httpServer;
    }

    @GET("/backend/config/general")
    String getGeneralConfig() throws Exception {
        GeneralConfig config = configService.getGeneralConfig();
        return mapper.writeValueAsString(GeneralConfigDtoBase.fromConfig(config));
    }

    @GET("/backend/config/ui")
    String getUserInterfaceConfig() throws Exception {
        // this code cannot be reached when httpServer is null
        checkNotNull(httpServer);
        return getUserInterface(false);
    }

    @GET("/backend/config/storage")
    String getStorageConfig() throws Exception {
        StorageConfig config = configService.getStorageConfig();
        return mapper.writeValueAsString(StorageConfigDtoBase.fromConfig(config));
    }

    @GET("/backend/config/smtp")
    String getSmtpConfig() throws Exception {
        SmtpConfig config = configService.getSmtpConfig();
        String localServerName = InetAddress.getLocalHost().getHostName();
        return mapper.writeValueAsString(SmtpConfigResponse.builder()
                .config(SmtpConfigDtoBase.fromConfig(config))
                .localServerName(localServerName)
                .build());
    }

    @GET("/backend/config/user-recording")
    String getUserRecordingConfig() throws Exception {
        UserRecordingConfig config = configService.getUserRecordingConfig();
        return mapper.writeValueAsString(UserRecordingConfigDtoBase.fromConfig(config));
    }

    @GET("/backend/config/advanced")
    String getAdvancedConfig() throws Exception {
        AdvancedConfig config = configService.getAdvancedConfig();
        return mapper.writeValueAsString(AdvancedConfigResponse.builder()
                .config(AdvancedConfigDtoBase.fromConfig(config))
                .timerWrapperMethodsActive(transactionModule.isTimerWrapperMethods())
                .build());
    }

    @GET("/backend/config/plugins")
    String getPlugins() throws Exception {
        List<PluginResponse> pluginResponses = Lists.newArrayList();
        for (PluginDescriptor pluginDescriptor : pluginDescriptors) {
            PluginConfig pluginConfig = configService.getPluginConfig(pluginDescriptor.id());
            checkNotNull(pluginConfig);
            pluginResponses.add(PluginResponse.builder()
                    .id(pluginDescriptor.id())
                    .name(pluginDescriptor.name())
                    .enabled(pluginConfig.enabled())
                    .build());
        }
        return mapper.writeValueAsString(pluginResponses);
    }

    @GET("/backend/config/plugin/(.+)")
    String getPluginConfig(String pluginId) throws Exception {
        PluginConfig config = configService.getPluginConfig(pluginId);
        PluginDescriptor pluginDescriptor = null;
        for (PluginDescriptor descriptor : pluginDescriptors) {
            if (descriptor.id().equals(pluginId)) {
                pluginDescriptor = descriptor;
                break;
            }
        }
        if (config == null || pluginDescriptor == null) {
            throw new IllegalArgumentException("Plugin id not found: " + pluginId);
        }
        return mapper.writeValueAsString(PluginConfigResponse.builder()
                .name(pluginDescriptor.name())
                .addAllPropertyDescriptors(pluginDescriptor.properties())
                .config(PluginConfigDtoBase.fromConfig(config))
                .build());
    }

    @POST("/backend/config/general")
    String updateGeneralConfig(String content) throws Exception {
        GeneralConfigDto configDto = mapper.readValue(content, GeneralConfigDto.class);
        try {
            configService.updateGeneralConfig(configDto.toConfig(), configDto.version());
        } catch (OptimisticLockException e) {
            throw new JsonServiceException(PRECONDITION_FAILED, e);
        }
        return getGeneralConfig();
    }

    @POST("/backend/config/ui")
    Object updateUserInterfaceConfig(String content) throws Exception {
        // this code cannot be reached when httpServer is null
        checkNotNull(httpServer);
        UserInterfaceConfigDto configDto = mapper.readValue(content, UserInterfaceConfigDto.class);
        configDto.validate();
        UserInterfaceConfig priorConfig = configService.getUserInterfaceConfig();
        UserInterfaceConfig config;
        try {
            config = toConfig(configDto, priorConfig);
        } catch (CurrentPasswordIncorrectException e) {
            return "{\"currentPasswordIncorrect\":true}";
        }
        try {
            configService.updateUserInterfaceConfig(config, configDto.version());
        } catch (OptimisticLockException e) {
            throw new JsonServiceException(PRECONDITION_FAILED, e);
        }
        return onSuccessfulUserInterfaceUpdate(priorConfig, config);
    }

    @POST("/backend/config/storage")
    String updateStorageConfig(String content) throws Exception {
        StorageConfigDto configDto = mapper.readValue(content, StorageConfigDto.class);
        try {
            configService.updateStorageConfig(configDto.toConfig(), configDto.version());
        } catch (OptimisticLockException e) {
            throw new JsonServiceException(PRECONDITION_FAILED, e);
        }
        // resize() doesn't do anything if the new and old value are the same
        cappedDatabase.resize(configService.getStorageConfig().cappedDatabaseSizeMb() * 1024);
        return getStorageConfig();
    }

    @POST("/backend/config/smtp")
    String updateSmtpConfig(String content) throws Exception {
        SmtpConfigDto configDto = mapper.readValue(content, SmtpConfigDto.class);
        try {
            configService.updateSmtpConfig(toConfig(configDto), configDto.version());
        } catch (OptimisticLockException e) {
            throw new JsonServiceException(PRECONDITION_FAILED, e);
        }
        return getSmtpConfig();
    }

    @POST("/backend/config/user-recording")
    String updateUserRecordingConfig(String content) throws Exception {
        UserRecordingConfigDto configDto = mapper.readValue(content, UserRecordingConfigDto.class);
        try {
            configService.updateUserRecordingConfig(configDto.toConfig(), configDto.version());
        } catch (OptimisticLockException e) {
            throw new JsonServiceException(PRECONDITION_FAILED, e);
        }
        return getUserRecordingConfig();
    }

    @POST("/backend/config/advanced")
    String updateAdvancedConfig(String content) throws Exception {
        AdvancedConfigDto configDto = mapper.readValue(content, AdvancedConfigDto.class);
        try {
            configService.updateAdvancedConfig(configDto.toConfig(), configDto.version());
        } catch (OptimisticLockException e) {
            throw new JsonServiceException(PRECONDITION_FAILED, e);
        }
        return getAdvancedConfig();
    }

    @POST("/backend/config/plugin/(.+)")
    String updatePluginConfig(String pluginId, String content) throws Exception {
        PluginConfigDto configDto = mapper.readValue(content, PluginConfigDto.class);
        try {
            configService.updatePluginConfig(configDto.toConfig(pluginId), configDto.version());
        } catch (OptimisticLockException e) {
            throw new JsonServiceException(PRECONDITION_FAILED, e);
        }
        return getPluginConfig(pluginId);
    }

    @POST("/backend/config/send-test-email")
    void sendTestEmail(String content) throws Exception {
        SmtpConfigDto configDto = mapper.readValue(content, SmtpConfigDto.class);
        String testEmailRecipient = configDto.testEmailRecipient();
        checkNotNull(testEmailRecipient);
        Session session = AlertingService.createMailSession(toConfig(configDto),
                configService.getSecretKey());
        Message message = new MimeMessage(session);
        String fromEmailAddress = configDto.fromEmailAddress();
        if (fromEmailAddress.isEmpty()) {
            String localServerName = InetAddress.getLocalHost().getHostName();
            fromEmailAddress = "glowroot@" + localServerName;
        }
        String fromDisplayName = configDto.fromDisplayName();
        if (fromDisplayName.isEmpty()) {
            fromDisplayName = "Glowroot";
        }
        message.setFrom(new InternetAddress(fromEmailAddress, fromDisplayName));
        InternetAddress to = new InternetAddress(testEmailRecipient);
        message.setRecipient(Message.RecipientType.TO, to);
        message.setSubject("Test email from Glowroot (EOM)");
        message.setText("");
        mailService.send(message);
    }

    @RequiresNonNull("httpServer")
    private Object onSuccessfulUserInterfaceUpdate(UserInterfaceConfig priorConfig,
            UserInterfaceConfig config) throws JsonProcessingException {
        boolean portChangedSucceeded = false;
        boolean portChangedFailed = false;
        if (priorConfig.port() != config.port()) {
            try {
                httpServer.changePort(config.port());
                portChangedSucceeded = true;
            } catch (PortChangeFailedException e) {
                logger.error(e.getMessage(), e);
                portChangedFailed = true;
            }
        }
        String responseText = getUserInterface(portChangedFailed);
        ByteBuf responseContent = Unpooled.copiedBuffer(responseText, Charsets.ISO_8859_1);
        FullHttpResponse response = new DefaultFullHttpResponse(HTTP_1_1, OK, responseContent);
        if (portChangedSucceeded) {
            response.headers().set("Glowroot-Port-Changed", "true");
        }
        // only create/delete session on successful update
        if (!priorConfig.adminPasswordEnabled() && config.adminPasswordEnabled()) {
            httpSessionManager.createSession(response, true);
        } else if (priorConfig.adminPasswordEnabled() && !config.adminPasswordEnabled()) {
            httpSessionManager.clearAllSessions();
            httpSessionManager.deleteSessionCookie(response);
        }
        return response;
    }

    @RequiresNonNull("httpServer")
    private String getUserInterface(boolean portChangeFailed) throws JsonProcessingException {
        UserInterfaceConfig config = configService.getUserInterfaceConfig();
        UserInterfaceConfigDto configDto = UserInterfaceConfigDto.builder()
                .port(config.port())
                .adminPasswordEnabled(config.adminPasswordEnabled())
                .readOnlyPasswordEnabled(config.readOnlyPasswordEnabled())
                .anonymousAccess(config.anonymousAccess())
                .sessionTimeoutMinutes(config.sessionTimeoutMinutes())
                .version(config.version())
                .build();
        return mapper.writeValueAsString(UserInterfaceConfigResponse.builder()
                .config(configDto)
                .activePort(httpServer.getPort())
                .portChangeFailed(portChangeFailed)
                .build());
    }

    private UserInterfaceConfig toConfig(UserInterfaceConfigDto configDto,
            UserInterfaceConfig priorConfig) throws Exception {
        UserInterfaceConfig.Builder builder = UserInterfaceConfig.builder()
                .port(configDto.port())
                .sessionTimeoutMinutes(configDto.sessionTimeoutMinutes());
        if (configDto.currentAdminPassword().length() > 0
                || configDto.newAdminPassword().length() > 0) {
            AdminPasswordHelper adminPasswordHelper = new AdminPasswordHelper(
                    configDto.currentAdminPassword(), configDto.newAdminPassword(),
                    priorConfig.adminPasswordHash());
            builder.adminPasswordHash(adminPasswordHelper.verifyAndGenerateNewPasswordHash());
        } else {
            builder.adminPasswordHash(priorConfig.adminPasswordHash());
        }
        if (!configDto.readOnlyPasswordEnabled()) {
            // clear read only password
            builder.readOnlyPasswordHash("");
        } else if (configDto.readOnlyPasswordEnabled()
                && !configDto.newReadOnlyPassword().isEmpty()) {
            // change read only password
            String readOnlyPasswordHash = PasswordHash.createHash(configDto.newReadOnlyPassword());
            builder.readOnlyPasswordHash(readOnlyPasswordHash);
        } else {
            // keep existing read only password
            builder.readOnlyPasswordHash(priorConfig.readOnlyPasswordHash());
        }
        if (priorConfig.anonymousAccess() != AnonymousAccess.ADMIN
                && configDto.anonymousAccess() == AnonymousAccess.ADMIN
                && configDto.currentAdminPassword().isEmpty()) {
            // enabling admin access for anonymous users requires admin password
            throw new IllegalStateException();
        }
        builder.anonymousAccess(configDto.anonymousAccess());
        return builder.build();
    }

    private SmtpConfig toConfig(SmtpConfigDto configDto) throws Exception {
        SmtpConfig.Builder builder = SmtpConfig.builder()
                .fromEmailAddress(configDto.fromEmailAddress())
                .fromDisplayName(configDto.fromDisplayName())
                .host(configDto.host())
                .port(configDto.port())
                .ssl(configDto.ssl())
                .username(configDto.username())
                .putAllAdditionalProperties(configDto.additionalProperties());
        if (!configDto.passwordExists()) {
            // clear password
            builder.encryptedPassword("");
        } else if (configDto.passwordExists() && !configDto.newPassword().isEmpty()) {
            // change password
            String newEncryptedPassword = Encryption.encrypt(configDto.newPassword(),
                    configService.getSecretKey());
            builder.encryptedPassword(newEncryptedPassword);
        } else {
            // keep existing password
            SmtpConfig priorConfig = configService.getSmtpConfig();
            builder.encryptedPassword(priorConfig.encryptedPassword());
        }
        return builder.build();
    }

    private static class AdminPasswordHelper {

        private final String currentPassword;
        private final String newPassword;
        private final String originalPasswordHash;

        private AdminPasswordHelper(String currentPassword, String newPassword,
                String originalPasswordHash) {
            this.currentPassword = currentPassword;
            this.newPassword = newPassword;
            this.originalPasswordHash = originalPasswordHash;
        }

        private String verifyAndGenerateNewPasswordHash() throws Exception {
            if (enablePassword()) {
                // UI validation prevents this from happening
                checkState(originalPasswordHash.isEmpty(), "Password is already enabled");
                return PasswordHash.createHash(newPassword);
            }
            if (!PasswordHash.validatePassword(currentPassword, originalPasswordHash)) {
                throw new CurrentPasswordIncorrectException();
            }
            if (disablePassword()) {
                return "";
            }
            if (changePassword()) {
                return PasswordHash.createHash(newPassword);
            }
            // UI validation prevents this from happening
            throw new IllegalStateException("Current and new password are both empty");
        }

        private boolean enablePassword() {
            return currentPassword.isEmpty() && !newPassword.isEmpty();
        }

        private boolean disablePassword() {
            return !currentPassword.isEmpty() && newPassword.isEmpty();
        }

        private boolean changePassword() {
            return !currentPassword.isEmpty() && !newPassword.isEmpty();
        }
    }

    @Value.Immutable
    @JsonSerialize
    abstract static class UserInterfaceConfigResponseBase {
        abstract UserInterfaceConfigDto config();
        abstract int activePort();
        abstract boolean portChangeFailed();
    }

    @Value.Immutable
    @JsonSerialize
    abstract static class SmtpConfigResponseBase {
        abstract SmtpConfigDto config();
        abstract String localServerName();
    }

    @Value.Immutable
    @JsonSerialize
    abstract static class AdvancedConfigResponseBase {
        abstract AdvancedConfigDto config();
        abstract boolean timerWrapperMethodsActive();
    }

    @Value.Immutable
    @JsonSerialize
    abstract static class PluginResponseBase {
        abstract String id();
        abstract String name();
        abstract boolean enabled();
    }

    @Value.Immutable
    @JsonSerialize
    abstract static class PluginConfigResponseBase {
        abstract String name();
        abstract PluginConfigDto config();
        abstract ImmutableList<PropertyDescriptor> propertyDescriptors();
    }

    // these DTOs are only different from underlying config objects in that they contain the version
    // attribute, and that they have no default attribute values

    @Value.Immutable
    @JsonSerialize
    abstract static class GeneralConfigDtoBase {

        abstract boolean enabled();
        abstract int traceStoreThresholdMillis();
        abstract int profilingIntervalMillis();
        abstract String defaultTransactionType();
        abstract String version();

        GeneralConfig toConfig() {
            return GeneralConfig.builder()
                    .enabled(enabled())
                    .traceStoreThresholdMillis(traceStoreThresholdMillis())
                    .profilingIntervalMillis(profilingIntervalMillis())
                    .defaultTransactionType(defaultTransactionType())
                    .build();
        }

        private static GeneralConfigDto fromConfig(GeneralConfig config) {
            return GeneralConfigDto.builder()
                    .enabled(config.enabled())
                    .traceStoreThresholdMillis(config.traceStoreThresholdMillis())
                    .profilingIntervalMillis(config.profilingIntervalMillis())
                    .defaultTransactionType(config.defaultTransactionType())
                    .version(config.version())
                    .build();
        }
    }

    @Value.Immutable
    @JsonSerialize
    abstract static class UserInterfaceConfigDtoBase {

        abstract int port();
        abstract boolean adminPasswordEnabled();
        abstract boolean readOnlyPasswordEnabled();
        abstract AnonymousAccess anonymousAccess();
        // only used for requests
        @Value.Default
        String currentAdminPassword() {
            return "";
        }
        // only used for requests
        @Value.Default
        String newAdminPassword() {
            return "";
        }
        // only used for requests
        @Value.Default
        String newReadOnlyPassword() {
            return "";
        }
        abstract int sessionTimeoutMinutes();
        abstract String version();

        void validate() {
            if (readOnlyPasswordEnabled()) {
                checkState(adminPasswordEnabled());
            }
            switch (anonymousAccess()) {
                case ADMIN:
                    checkState(!adminPasswordEnabled());
                    checkState(!readOnlyPasswordEnabled());
                    break;
                case READ_ONLY:
                    checkState(adminPasswordEnabled());
                    checkState(!readOnlyPasswordEnabled());
                    break;
                case NONE:
                    checkState(adminPasswordEnabled());
                    break;
                default:
                    throw new IllegalStateException("Unexpected anonymous access: "
                            + anonymousAccess());
            }
        }
    }

    @Value.Immutable
    @JsonSerialize
    abstract static class StorageConfigDtoBase {

        abstract int aggregateExpirationHours();
        abstract int traceExpirationHours();
        abstract int gaugeExpirationHours();
        abstract int cappedDatabaseSizeMb();
        abstract String version();

        StorageConfig toConfig() {
            return StorageConfig.builder()
                    .aggregateExpirationHours(aggregateExpirationHours())
                    .traceExpirationHours(traceExpirationHours())
                    .gaugeExpirationHours(gaugeExpirationHours())
                    .cappedDatabaseSizeMb(cappedDatabaseSizeMb())
                    .build();
        }

        private static StorageConfigDto fromConfig(StorageConfig config) {
            return StorageConfigDto.builder()
                    .aggregateExpirationHours(config.aggregateExpirationHours())
                    .traceExpirationHours(config.traceExpirationHours())
                    .gaugeExpirationHours(config.gaugeExpirationHours())
                    .cappedDatabaseSizeMb(config.cappedDatabaseSizeMb())
                    .version(config.version())
                    .build();
        }
    }

    @Value.Immutable
    @JsonSerialize
    abstract static class SmtpConfigDtoBase {

        abstract String fromEmailAddress();
        abstract String fromDisplayName();
        abstract String host();
        abstract @Nullable Integer port();
        abstract boolean ssl();
        abstract String username();
        abstract boolean passwordExists();
        abstract Map<String, String> additionalProperties();
        // only used for requests
        @Value.Default
        String newPassword() {
            return "";
        }
        // only used for requests
        abstract @Nullable String testEmailRecipient();
        abstract String version();

        private static SmtpConfigDto fromConfig(SmtpConfig config) {
            return SmtpConfigDto.builder()
                    .fromEmailAddress(config.fromEmailAddress())
                    .fromDisplayName(config.fromDisplayName())
                    .host(config.host())
                    .port(config.port())
                    .ssl(config.ssl())
                    .username(config.username())
                    .passwordExists(!config.encryptedPassword().isEmpty())
                    .putAllAdditionalProperties(config.additionalProperties())
                    .version(config.version())
                    .build();
        }
    }

    @Value.Immutable
    @JsonSerialize
    abstract static class UserRecordingConfigDtoBase {

        abstract boolean enabled();
        abstract String user();
        abstract int profileIntervalMillis();
        abstract String version();

        UserRecordingConfig toConfig() {
            return UserRecordingConfig.builder()
                    .enabled(enabled())
                    .user(user())
                    .profileIntervalMillis(profileIntervalMillis())
                    .build();
        }

        private static UserRecordingConfigDto fromConfig(UserRecordingConfig config) {
            return UserRecordingConfigDto.builder()
                    .enabled(config.enabled())
                    .user(config.user())
                    .profileIntervalMillis(config.profileIntervalMillis())
                    .version(config.version())
                    .build();
        }
    }

    @Value.Immutable
    @JsonSerialize
    abstract static class AdvancedConfigDtoBase {

        abstract boolean timerWrapperMethods();
        abstract boolean weavingTimer();
        abstract int immediatePartialStoreThresholdSeconds();
        abstract int maxAggregateQueriesPerQueryType();
        abstract int maxTraceEntriesPerTransaction();
        abstract int maxStackTraceSamplesPerTransaction();
        abstract boolean captureThreadInfo();
        abstract boolean captureGcInfo();
        abstract int mbeanGaugeNotFoundDelaySeconds();
        abstract int internalQueryTimeoutSeconds();
        abstract String version();

        AdvancedConfig toConfig() {
            return AdvancedConfig.builder()
                    .timerWrapperMethods(timerWrapperMethods())
                    .weavingTimer(weavingTimer())
                    .immediatePartialStoreThresholdSeconds(immediatePartialStoreThresholdSeconds())
                    .maxAggregateQueriesPerQueryType(maxAggregateQueriesPerQueryType())
                    .maxTraceEntriesPerTransaction(maxTraceEntriesPerTransaction())
                    .maxStackTraceSamplesPerTransaction(maxStackTraceSamplesPerTransaction())
                    .captureThreadInfo(captureThreadInfo())
                    .captureGcInfo(captureGcInfo())
                    .mbeanGaugeNotFoundDelaySeconds(mbeanGaugeNotFoundDelaySeconds())
                    .internalQueryTimeoutSeconds(internalQueryTimeoutSeconds())
                    .build();
        }

        private static AdvancedConfigDto fromConfig(AdvancedConfig config) {
            return AdvancedConfigDto.builder()
                    .timerWrapperMethods(config.timerWrapperMethods())
                    .weavingTimer(config.weavingTimer())
                    .immediatePartialStoreThresholdSeconds(
                            config.immediatePartialStoreThresholdSeconds())
                    .maxAggregateQueriesPerQueryType(config.maxAggregateQueriesPerQueryType())
                    .maxTraceEntriesPerTransaction(config.maxTraceEntriesPerTransaction())
                    .maxStackTraceSamplesPerTransaction(
                            config.maxStackTraceSamplesPerTransaction())
                    .captureThreadInfo(config.captureThreadInfo())
                    .captureGcInfo(config.captureGcInfo())
                    .mbeanGaugeNotFoundDelaySeconds(config.mbeanGaugeNotFoundDelaySeconds())
                    .internalQueryTimeoutSeconds(config.internalQueryTimeoutSeconds())
                    .version(config.version())
                    .build();
        }
    }

    @Value.Immutable
    @JsonSerialize
    abstract static class PluginConfigDtoBase {

        abstract boolean enabled();
        abstract Map<String, PropertyValue> properties();
        abstract String version();

        PluginConfig toConfig(String id) {
            return PluginConfig.builder()
                    .id(id)
                    .enabled(enabled())
                    .putAllProperties(properties())
                    .build();
        }

        private static PluginConfigDto fromConfig(PluginConfig config) {
            return PluginConfigDto.builder()
                    .enabled(config.enabled())
                    .putAllProperties(config.properties())
                    .version(config.version())
                    .build();
        }
    }

    @SuppressWarnings("serial")
    private static class CurrentPasswordIncorrectException extends Exception {}
}
