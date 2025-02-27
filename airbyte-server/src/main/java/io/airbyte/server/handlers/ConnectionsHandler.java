/*
 * Copyright (c) 2021 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.server.handlers;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMap.Builder;
import com.google.common.collect.Lists;
import io.airbyte.analytics.TrackingClient;
import io.airbyte.api.model.ConnectionCreate;
import io.airbyte.api.model.ConnectionIdRequestBody;
import io.airbyte.api.model.ConnectionRead;
import io.airbyte.api.model.ConnectionReadList;
import io.airbyte.api.model.ConnectionSchedule;
import io.airbyte.api.model.ConnectionSearch;
import io.airbyte.api.model.ConnectionStatus;
import io.airbyte.api.model.ConnectionUpdate;
import io.airbyte.api.model.DestinationRead;
import io.airbyte.api.model.DestinationSearch;
import io.airbyte.api.model.ResourceRequirements;
import io.airbyte.api.model.SourceRead;
import io.airbyte.api.model.SourceSearch;
import io.airbyte.api.model.WorkspaceIdRequestBody;
import io.airbyte.commons.enums.Enums;
import io.airbyte.commons.json.Jsons;
import io.airbyte.config.DestinationConnection;
import io.airbyte.config.JobSyncConfig.NamespaceDefinitionType;
import io.airbyte.config.Schedule;
import io.airbyte.config.SourceConnection;
import io.airbyte.config.StandardDestinationDefinition;
import io.airbyte.config.StandardSourceDefinition;
import io.airbyte.config.StandardSync;
import io.airbyte.config.helpers.ScheduleHelpers;
import io.airbyte.config.persistence.ConfigNotFoundException;
import io.airbyte.config.persistence.ConfigRepository;
import io.airbyte.protocol.models.ConfiguredAirbyteCatalog;
import io.airbyte.scheduler.persistence.WorkspaceHelper;
import io.airbyte.server.converters.CatalogConverter;
import io.airbyte.server.handlers.helpers.ConnectionMatcher;
import io.airbyte.server.handlers.helpers.DestinationMatcher;
import io.airbyte.server.handlers.helpers.SourceMatcher;
import io.airbyte.validation.json.JsonValidationException;
import io.airbyte.workers.WorkerUtils;
import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ConnectionsHandler {

  private static final Logger LOGGER = LoggerFactory.getLogger(ConnectionsHandler.class);

  private final ConfigRepository configRepository;
  private final Supplier<UUID> uuidGenerator;
  private final WorkspaceHelper workspaceHelper;
  private final TrackingClient trackingClient;

  @VisibleForTesting
  ConnectionsHandler(final ConfigRepository configRepository,
                     final Supplier<UUID> uuidGenerator,
                     final WorkspaceHelper workspaceHelper,
                     TrackingClient trackingClient) {
    this.configRepository = configRepository;
    this.uuidGenerator = uuidGenerator;
    this.workspaceHelper = workspaceHelper;
    this.trackingClient = trackingClient;
  }

  public ConnectionsHandler(final ConfigRepository configRepository, final WorkspaceHelper workspaceHelper, TrackingClient trackingClient) {
    this(configRepository, UUID::randomUUID, workspaceHelper, trackingClient);
  }

  private void validateWorkspace(UUID sourceId, UUID destinationId, Set<UUID> operationIds) {
    final UUID sourceWorkspace = workspaceHelper.getWorkspaceForSourceIdIgnoreExceptions(sourceId);
    final UUID destinationWorkspace = workspaceHelper.getWorkspaceForDestinationIdIgnoreExceptions(destinationId);

    Preconditions.checkArgument(
        sourceWorkspace.equals(destinationWorkspace),
        String.format(
            "Source and destination do not belong to the same workspace. Source id: %s, Source workspace id: %s, Destination id: %s, Destination workspace id: %s",
            sourceId,
            sourceWorkspace,
            destinationId,
            destinationWorkspace));

    for (UUID operationId : operationIds) {
      final UUID operationWorkspace = workspaceHelper.getWorkspaceForOperationIdIgnoreExceptions(operationId);
      Preconditions.checkArgument(
          sourceWorkspace.equals(operationWorkspace),
          String.format(
              "Operation and connection do not belong to the same workspace. Workspace id: %s, Operation id: %s, Operation workspace id: %s",
              sourceWorkspace,
              operationId,
              operationWorkspace));
    }
  }

  public ConnectionRead createConnection(ConnectionCreate connectionCreate) throws JsonValidationException, IOException, ConfigNotFoundException {
    // Validate source and destination
    configRepository.getSourceConnection(connectionCreate.getSourceId());
    configRepository.getDestinationConnection(connectionCreate.getDestinationId());
    validateWorkspace(connectionCreate.getSourceId(), connectionCreate.getDestinationId(), new HashSet<>(connectionCreate.getOperationIds()));

    final UUID connectionId = uuidGenerator.get();

    // persist sync
    final StandardSync standardSync = new StandardSync()
        .withConnectionId(connectionId)
        .withName(connectionCreate.getName() != null ? connectionCreate.getName() : "default")
        .withNamespaceDefinition(Enums.convertTo(connectionCreate.getNamespaceDefinition(), NamespaceDefinitionType.class))
        .withNamespaceFormat(connectionCreate.getNamespaceFormat())
        .withPrefix(connectionCreate.getPrefix())
        .withSourceId(connectionCreate.getSourceId())
        .withDestinationId(connectionCreate.getDestinationId())
        .withOperationIds(connectionCreate.getOperationIds())
        .withStatus(toPersistenceStatus(connectionCreate.getStatus()));
    if (connectionCreate.getResourceRequirements() != null) {
      standardSync.withResourceRequirements(new io.airbyte.config.ResourceRequirements()
          .withCpuRequest(connectionCreate.getResourceRequirements().getCpuRequest())
          .withCpuLimit(connectionCreate.getResourceRequirements().getCpuLimit())
          .withMemoryRequest(connectionCreate.getResourceRequirements().getMemoryRequest())
          .withMemoryLimit(connectionCreate.getResourceRequirements().getMemoryLimit()));
    } else {
      standardSync.withResourceRequirements(WorkerUtils.DEFAULT_RESOURCE_REQUIREMENTS);
    }

    // TODO Undesirable behavior: sending a null configured catalog should not be valid?
    if (connectionCreate.getSyncCatalog() != null) {
      standardSync.withCatalog(CatalogConverter.toProtocol(connectionCreate.getSyncCatalog()));
    } else {
      standardSync.withCatalog(new ConfiguredAirbyteCatalog().withStreams(Collections.emptyList()));
    }

    if (connectionCreate.getSchedule() != null) {
      final Schedule schedule = new Schedule()
          .withTimeUnit(toPersistenceTimeUnit(connectionCreate.getSchedule().getTimeUnit()))
          .withUnits(connectionCreate.getSchedule().getUnits());
      standardSync
          .withManual(false)
          .withSchedule(schedule);
    } else {
      standardSync.withManual(true);
    }

    configRepository.writeStandardSync(standardSync);

    trackNewConnection(standardSync);

    return buildConnectionRead(connectionId);
  }

  private void trackNewConnection(final StandardSync standardSync) {
    try {
      final UUID workspaceId = workspaceHelper.getWorkspaceForConnectionIdIgnoreExceptions(standardSync.getConnectionId());
      final Builder<String, Object> metadataBuilder = generateMetadata(standardSync);
      trackingClient.track(workspaceId, "New Connection - Backend", metadataBuilder.build());
    } catch (Exception e) {
      LOGGER.error("failed while reporting usage.", e);
    }
  }

  private Builder<String, Object> generateMetadata(final StandardSync standardSync) {
    final Builder<String, Object> metadata = ImmutableMap.builder();

    final UUID connectionId = standardSync.getConnectionId();
    final StandardSourceDefinition sourceDefinition = configRepository
        .getSourceDefinitionFromConnection(connectionId);
    final StandardDestinationDefinition destinationDefinition = configRepository
        .getDestinationDefinitionFromConnection(connectionId);

    metadata.put("connector_source", sourceDefinition.getName());
    metadata.put("connector_source_definition_id", sourceDefinition.getSourceDefinitionId());
    metadata.put("connector_destination", destinationDefinition.getName());
    metadata.put("connector_destination_definition_id", destinationDefinition.getDestinationDefinitionId());

    final String frequencyString;
    if (standardSync.getManual()) {
      frequencyString = "manual";
    } else {
      final long intervalInMinutes = TimeUnit.SECONDS.toMinutes(ScheduleHelpers.getIntervalInSecond(standardSync.getSchedule()));
      frequencyString = intervalInMinutes + " min";
    }
    metadata.put("frequency", frequencyString);
    return metadata;
  }

  public ConnectionRead updateConnection(ConnectionUpdate connectionUpdate) throws ConfigNotFoundException, IOException, JsonValidationException {
    // retrieve and update sync
    final StandardSync persistedSync = configRepository.getStandardSync(connectionUpdate.getConnectionId());

    validateWorkspace(persistedSync.getSourceId(), persistedSync.getDestinationId(), new HashSet<>(connectionUpdate.getOperationIds()));

    final StandardSync newConnection = Jsons.clone(persistedSync)
        .withNamespaceDefinition(Enums.convertTo(connectionUpdate.getNamespaceDefinition(), NamespaceDefinitionType.class))
        .withNamespaceFormat(connectionUpdate.getNamespaceFormat())
        .withPrefix(connectionUpdate.getPrefix())
        .withOperationIds(connectionUpdate.getOperationIds())
        .withCatalog(CatalogConverter.toProtocol(connectionUpdate.getSyncCatalog()))
        .withStatus(toPersistenceStatus(connectionUpdate.getStatus()));

    // update Resource Requirements
    if (connectionUpdate.getResourceRequirements() != null) {
      newConnection.withResourceRequirements(new io.airbyte.config.ResourceRequirements()
          .withCpuRequest(connectionUpdate.getResourceRequirements().getCpuRequest())
          .withCpuLimit(connectionUpdate.getResourceRequirements().getCpuLimit())
          .withMemoryRequest(connectionUpdate.getResourceRequirements().getMemoryRequest())
          .withMemoryLimit(connectionUpdate.getResourceRequirements().getMemoryLimit()));
    } else {
      newConnection.withResourceRequirements(WorkerUtils.DEFAULT_RESOURCE_REQUIREMENTS);
    }

    // update sync schedule
    if (connectionUpdate.getSchedule() != null) {
      final Schedule newSchedule = new Schedule()
          .withTimeUnit(toPersistenceTimeUnit(connectionUpdate.getSchedule().getTimeUnit()))
          .withUnits(connectionUpdate.getSchedule().getUnits());
      newConnection.withManual(false).withSchedule(newSchedule);
    } else {
      newConnection.withManual(true).withSchedule(null);
    }

    configRepository.writeStandardSync(newConnection);
    return buildConnectionRead(connectionUpdate.getConnectionId());
  }

  public ConnectionReadList listConnectionsForWorkspace(WorkspaceIdRequestBody workspaceIdRequestBody)
      throws JsonValidationException, IOException, ConfigNotFoundException {
    final List<ConnectionRead> connectionReads = Lists.newArrayList();

    for (StandardSync standardSync : configRepository.listStandardSyncs()) {
      if (standardSync.getStatus() == StandardSync.Status.DEPRECATED) {
        continue;
      }
      if (!isStandardSyncInWorkspace(workspaceIdRequestBody.getWorkspaceId(), standardSync)) {
        continue;
      }

      connectionReads.add(buildConnectionRead(standardSync.getConnectionId()));
    }

    return new ConnectionReadList().connections(connectionReads);
  }

  public ConnectionReadList listConnections() throws JsonValidationException, ConfigNotFoundException, IOException {
    final List<ConnectionRead> connectionReads = Lists.newArrayList();

    for (StandardSync standardSync : configRepository.listStandardSyncs()) {
      if (standardSync.getStatus() == StandardSync.Status.DEPRECATED) {
        continue;
      }
      connectionReads.add(buildConnectionRead(standardSync.getConnectionId()));
    }

    return new ConnectionReadList().connections(connectionReads);
  }

  public ConnectionRead getConnection(ConnectionIdRequestBody connectionIdRequestBody)
      throws JsonValidationException, IOException, ConfigNotFoundException {
    return buildConnectionRead(connectionIdRequestBody.getConnectionId());
  }

  public ConnectionReadList searchConnections(ConnectionSearch connectionSearch)
      throws JsonValidationException, IOException, ConfigNotFoundException {
    final List<ConnectionRead> reads = Lists.newArrayList();
    for (StandardSync standardSync : configRepository.listStandardSyncs()) {
      if (standardSync.getStatus() != StandardSync.Status.DEPRECATED) {
        ConnectionRead connectionRead = buildConnectionRead(standardSync.getConnectionId());
        if (matchSearch(connectionSearch, connectionRead)) {
          reads.add(connectionRead);
        }
      }
    }

    return new ConnectionReadList().connections(reads);
  }

  public boolean matchSearch(ConnectionSearch connectionSearch, ConnectionRead connectionRead)
      throws JsonValidationException, ConfigNotFoundException, IOException {

    final SourceConnection sourceConnection = configRepository.getSourceConnection(connectionRead.getSourceId());
    final StandardSourceDefinition sourceDefinition =
        configRepository.getStandardSourceDefinition(sourceConnection.getSourceDefinitionId());
    SourceRead sourceRead = SourceHandler.toSourceRead(sourceConnection, sourceDefinition);

    final DestinationConnection destinationConnection = configRepository.getDestinationConnection(connectionRead.getDestinationId());
    final StandardDestinationDefinition destinationDefinition =
        configRepository.getStandardDestinationDefinition(destinationConnection.getDestinationDefinitionId());
    DestinationRead destinationRead = DestinationHandler.toDestinationRead(destinationConnection, destinationDefinition);

    final ConnectionMatcher connectionMatcher = new ConnectionMatcher(connectionSearch);
    final ConnectionRead connectionReadFromSearch = connectionMatcher.match(connectionRead);

    return (connectionReadFromSearch == null || connectionReadFromSearch.equals(connectionRead)) &&
        matchSearch(connectionSearch.getSource(), sourceRead) &&
        matchSearch(connectionSearch.getDestination(), destinationRead);
  }

  public boolean matchSearch(SourceSearch sourceSearch, SourceRead sourceRead) {
    final SourceMatcher sourceMatcher = new SourceMatcher(sourceSearch);
    final SourceRead sourceReadFromSearch = sourceMatcher.match(sourceRead);

    return (sourceReadFromSearch == null || sourceReadFromSearch.equals(sourceRead));
  }

  public boolean matchSearch(DestinationSearch destinationSearch, DestinationRead destinationRead) {
    final DestinationMatcher destinationMatcher = new DestinationMatcher(destinationSearch);
    final DestinationRead destinationReadFromSearch = destinationMatcher.match(destinationRead);

    return (destinationReadFromSearch == null || destinationReadFromSearch.equals(destinationRead));
  }

  public void deleteConnection(ConnectionIdRequestBody connectionIdRequestBody) throws ConfigNotFoundException, IOException, JsonValidationException {
    final ConnectionRead connectionRead = getConnection(connectionIdRequestBody);
    deleteConnection(connectionRead);
  }

  public void deleteConnection(ConnectionRead connectionRead) throws ConfigNotFoundException, IOException, JsonValidationException {
    final ConnectionUpdate connectionUpdate = new ConnectionUpdate()
        .namespaceDefinition(connectionRead.getNamespaceDefinition())
        .namespaceFormat(connectionRead.getNamespaceFormat())
        .prefix(connectionRead.getPrefix())
        .connectionId(connectionRead.getConnectionId())
        .operationIds(connectionRead.getOperationIds())
        .syncCatalog(connectionRead.getSyncCatalog())
        .schedule(connectionRead.getSchedule())
        .status(ConnectionStatus.DEPRECATED)
        .resourceRequirements(connectionRead.getResourceRequirements());

    updateConnection(connectionUpdate);
  }

  private boolean isStandardSyncInWorkspace(final UUID workspaceId,
                                            final StandardSync standardSync)
      throws ConfigNotFoundException, IOException, JsonValidationException {
    return configRepository.getSourceConnection(standardSync.getSourceId()).getWorkspaceId().equals(workspaceId);
  }

  private ConnectionRead buildConnectionRead(UUID connectionId)
      throws ConfigNotFoundException, IOException, JsonValidationException {
    final StandardSync standardSync = configRepository.getStandardSync(connectionId);
    return buildConnectionRead(standardSync);
  }

  private ConnectionRead buildConnectionRead(final StandardSync standardSync) {
    ConnectionSchedule apiSchedule = null;

    if (!standardSync.getManual()) {
      apiSchedule = new ConnectionSchedule()
          .timeUnit(toApiTimeUnit(standardSync.getSchedule().getTimeUnit()))
          .units(standardSync.getSchedule().getUnits());
    }

    final ConnectionRead connectionRead = new ConnectionRead()
        .connectionId(standardSync.getConnectionId())
        .sourceId(standardSync.getSourceId())
        .destinationId(standardSync.getDestinationId())
        .operationIds(standardSync.getOperationIds())
        .status(toApiStatus(standardSync.getStatus()))
        .schedule(apiSchedule)
        .name(standardSync.getName())
        .namespaceDefinition(Enums.convertTo(standardSync.getNamespaceDefinition(), io.airbyte.api.model.NamespaceDefinitionType.class))
        .namespaceFormat(standardSync.getNamespaceFormat())
        .prefix(standardSync.getPrefix())
        .syncCatalog(CatalogConverter.toApi(standardSync.getCatalog()));

    if (standardSync.getResourceRequirements() != null) {
      connectionRead.resourceRequirements(new ResourceRequirements()
          .cpuRequest(standardSync.getResourceRequirements().getCpuRequest())
          .cpuLimit(standardSync.getResourceRequirements().getCpuLimit())
          .memoryRequest(standardSync.getResourceRequirements().getMemoryRequest())
          .memoryLimit(standardSync.getResourceRequirements().getMemoryLimit()));
    } else {
      connectionRead.resourceRequirements(new ResourceRequirements()
          .cpuRequest(WorkerUtils.DEFAULT_RESOURCE_REQUIREMENTS.getCpuRequest())
          .cpuLimit(WorkerUtils.DEFAULT_RESOURCE_REQUIREMENTS.getCpuLimit())
          .memoryRequest(WorkerUtils.DEFAULT_RESOURCE_REQUIREMENTS.getMemoryRequest())
          .memoryLimit(WorkerUtils.DEFAULT_RESOURCE_REQUIREMENTS.getMemoryLimit()));
    }
    return connectionRead;
  }

  private StandardSync.Status toPersistenceStatus(ConnectionStatus apiStatus) {
    return Enums.convertTo(apiStatus, StandardSync.Status.class);
  }

  private ConnectionStatus toApiStatus(StandardSync.Status status) {
    return Enums.convertTo(status, ConnectionStatus.class);
  }

  private Schedule.TimeUnit toPersistenceTimeUnit(ConnectionSchedule.TimeUnitEnum apiTimeUnit) {
    return Enums.convertTo(apiTimeUnit, Schedule.TimeUnit.class);
  }

  private ConnectionSchedule.TimeUnitEnum toApiTimeUnit(Schedule.TimeUnit apiTimeUnit) {
    return Enums.convertTo(apiTimeUnit, ConnectionSchedule.TimeUnitEnum.class);
  }

}
