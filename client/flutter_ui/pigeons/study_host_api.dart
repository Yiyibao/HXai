import 'package:pigeon/pigeon.dart';

@ConfigurePigeon(PigeonOptions(
  dartOut: 'lib/bridge/study_host_api.g.dart',
  dartOptions: DartOptions(),
  kotlinOut: '../app/src/main/java/me/rerere/rikkahub/flutter/generated/StudyHostApi.g.kt',
  kotlinOptions: KotlinOptions(
    package: 'me.rerere.rikkahub.flutter.generated',
  ),
))

class StudyErrorDto {
  StudyErrorDto({
    required this.code,
    required this.message,
    required this.retryable,
    required this.requestId,
  });
  final String code;
  final String message;
  final bool retryable;
  final String requestId;
}

class SimpleCommandResult {
  SimpleCommandResult({
    required this.success,
    this.error,
    this.idResult,
  });
  final bool success;
  final StudyErrorDto? error;
  final String? idResult;
}

class LearnerProfileDto {
  LearnerProfileDto({
    required this.id,
    required this.name,
    required this.memoryEnabled,
    required this.activeStudyMode,
  });
  final String id;
  final String name;
  final bool memoryEnabled;
  final String activeStudyMode;
}

class ModelDto {
  ModelDto({
    required this.id,
    required this.name,
    required this.providerId,
    required this.enabled,
    required this.supportText,
    required this.supportImage,
    required this.supportTool,
  });
  final String id;
  final String name;
  final String providerId;
  final bool enabled;
  final bool supportText;
  final bool supportImage;
  final bool supportTool;
}

class ProviderDto {
  ProviderDto({
    required this.id,
    required this.name,
    required this.type,
    required this.endpoint,
    required this.apiKeyMasked,
    required this.enabled,
    required this.isPrimary,
    required this.models,
  });
  final String id;
  final String name;
  final String type;
  final String endpoint;
  final String apiKeyMasked;
  final bool enabled;
  final bool isPrimary;
  final List<ModelDto> models;
}

class StudyBootstrapDto {
  StudyBootstrapDto({
    required this.currentProfileId,
    required this.contextEpoch,
    required this.profiles,
    required this.studyMode,
    required this.reduceMotion,
    required this.darkMode,
    this.activeModelId,
    this.activeModelName,
    this.activeProviderId,
    this.activeProviderName,
    this.defaultConversationId,
  });
  final String currentProfileId;
  final int contextEpoch;
  final List<LearnerProfileDto> profiles;
  final String studyMode;
  final bool reduceMotion;
  final bool darkMode;
  final String? activeModelId;
  final String? activeModelName;
  final String? activeProviderId;
  final String? activeProviderName;
  final String? defaultConversationId;
}

class MessagePartDto {
  MessagePartDto({
    required this.type,
    this.text,
    this.url,
    this.fileName,
    this.mime,
    this.reasoning,
    this.toolCallId,
    this.toolName,
    this.toolInput,
    this.toolOutput,
    this.approvalState,
    this.executionState,
    this.artifactId,
    this.surfaceId,
    this.surfaceTitle,
    this.surfaceDefinition,
    this.surfaceRevision,
  });
  final String type;
  final String? text;
  final String? url;
  final String? fileName;
  final String? mime;
  final String? reasoning;
  final String? toolCallId;
  final String? toolName;
  final String? toolInput;
  final String? toolOutput;
  final String? approvalState;
  final String? executionState;
  final String? artifactId;
  // Interactive Surface 字段
  final String? surfaceId;
  final String? surfaceTitle;
  final String? surfaceDefinition;
  final int? surfaceRevision;
}

class MessageDto {
  MessageDto({
    required this.id,
    required this.nodeId,
    required this.role,
    required this.parts,
    required this.createdAt,
    required this.revision,
    this.generationState,
  });
  final String id;
  final String nodeId;
  final String role;
  final List<MessagePartDto> parts;
  final String createdAt;
  final int revision;
  final String? generationState;
}

class MessageNodeDto {
  MessageNodeDto({
    required this.nodeId,
    required this.selectIndex,
    required this.branches,
  });
  final String nodeId;
  final int selectIndex;
  final List<MessageDto> branches;
}

class ConversationSummaryDto {
  ConversationSummaryDto({
    required this.id,
    required this.title,
    required this.previewText,
    required this.updatedAt,
    required this.isPinned,
    required this.studyMode,
    required this.profileId,
    required this.isArchived,
  });
  final String id;
  final String title;
  final String previewText;
  final String updatedAt;
  final bool isPinned;
  final String studyMode;
  final String profileId;
  final bool isArchived;
}

class ProjectDto {
  ProjectDto({required this.id, required this.name, required this.conversations});
  final String id;
  final String name;
  final List<ConversationSummaryDto> conversations;
}

class AgentTaskDto {
  AgentTaskDto({
    required this.id, required this.owner, required this.state,
    required this.providerId, required this.modelId, required this.reasoning,
    required this.providerName, required this.modelName, required this.providerOrigin,
    required this.revision, required this.fence, required this.grantEpoch,
    required this.authorization, required this.manualPaused,
    required this.inputBlocked, required this.deviceBlocked,
    required this.deviceConfirmation, required this.goalRevision,
    required this.plannedRevision, required this.acceptanceVerified,
    required this.verifiedSteps, required this.approvalCallIds,
    this.credentialRequestId, this.credentialState,
  });
  final String id;
  final String owner;
  final String state;
  final String providerId;
  final String modelId;
  final String reasoning;
  final String providerName;
  final String modelName;
  final String providerOrigin;
  final int revision;
  final int fence;
  final int grantEpoch;
  final String authorization;
  final bool manualPaused;
  final bool inputBlocked;
  final bool deviceBlocked;
  final bool deviceConfirmation;
  final int goalRevision;
  final int plannedRevision;
  final bool acceptanceVerified;
  final List<String> verifiedSteps;
  final List<String> approvalCallIds;
  final String? credentialRequestId;
  final String? credentialState;
}

class AgentUsageMetricDto {
  AgentUsageMetricDto({this.reported, this.estimated, required this.unknownRequests});
  final int? reported;
  final int? estimated;
  final int unknownRequests;
}

class AgentCatalogTaskDto {
  AgentCatalogTaskDto({required this.id, required this.owner, required this.conversationId,
    required this.role, required this.goal, required this.state, required this.providerId,
    required this.modelId, required this.reasoning, required this.revision, required this.fence,
    required this.manualPaused, required this.authorization, required this.inputBlocked,
    required this.deviceBlocked, required this.deviceConfirmation, required this.approvalBlocked,
    required this.goalRevision, required this.plannedRevision, required this.acceptanceVerified});
  final String id;
  final String owner;
  final String conversationId;
  final String role;
  final String goal;
  final String state;
  final String providerId;
  final String modelId;
  final String reasoning;
  final int revision;
  final int fence;
  final int goalRevision;
  final int plannedRevision;
  final bool manualPaused;
  final bool inputBlocked;
  final bool deviceBlocked;
  final bool deviceConfirmation;
  final bool approvalBlocked;
  final bool acceptanceVerified;
  final String authorization;
}
class AgentCatalogPageDto {
  AgentCatalogPageDto({required this.owner, required this.kind, required this.tasks, this.nextCursor});
  final String owner;
  final String kind;
  final List<AgentCatalogTaskDto> tasks;
  final String? nextCursor;
}
class AgentCatalogStepDto {
  AgentCatalogStepDto({required this.id, required this.tool, required this.purpose, required this.state, this.callId});
  final String id;
  final String tool;
  final String purpose;
  final String state;
  final String? callId;
}
class AgentCatalogCallDto {
  AgentCatalogCallDto({required this.id, required this.tool, required this.state, required this.inputHash,
    this.resultHash, this.capability, this.grantEpoch});
  final String id;
  final String tool;
  final String state;
  final String inputHash;
  final String? resultHash;
  final String? capability;
  final int? grantEpoch;
}
class AgentCatalogArtifactDto {
  AgentCatalogArtifactDto({required this.id, required this.name, required this.sha256});
  final String id;
  final String name;
  final String sha256;
}
class AgentCatalogDetailDto {
  AgentCatalogDetailDto({required this.task, required this.steps, required this.calls, required this.artifacts,
    required this.resultsPartial, required this.conversationAvailable, this.nextCursor, this.providerName, this.modelName});
  final AgentCatalogTaskDto task;
  final List<AgentCatalogStepDto> steps;
  final List<AgentCatalogCallDto> calls;
  final List<AgentCatalogArtifactDto> artifacts;
  final bool resultsPartial;
  final bool conversationAvailable;
  final String? nextCursor;
  final String? providerName;
  final String? modelName;
}

class AgentUsageEntryDto {
  AgentUsageEntryDto({required this.requestId, required this.taskId, required this.conversationId,
    required this.role, this.providerId, this.modelId, this.reasoning, this.createdAt,
    required this.state, required this.provenance, this.inputTokens, this.outputTokens,
    this.cachedTokens, this.totalTokens});
  final String requestId;
  final String taskId;
  final String conversationId;
  final String role;
  final String? providerId;
  final String? modelId;
  final String? reasoning;
  final int? createdAt;
  final String state;
  final String provenance;
  final int? inputTokens;
  final int? outputTokens;
  final int? cachedTokens;
  final int? totalTokens;
}

class AgentUsagePageDto {
  AgentUsagePageDto({required this.owner, this.taskId, this.from, this.until,
    required this.requestCount, required this.reportedRequests, required this.estimatedRequests,
    required this.unknownRequests, required this.undatedRequests, required this.input,
    required this.output, required this.cached, required this.total, required this.entries, this.nextCursor});
  final String owner;
  final String? taskId;
  final int? from;
  final int? until;
  final int requestCount;
  final int reportedRequests;
  final int estimatedRequests;
  final int unknownRequests;
  final int undatedRequests;
  final AgentUsageMetricDto input;
  final AgentUsageMetricDto output;
  final AgentUsageMetricDto cached;
  final AgentUsageMetricDto total;
  final List<AgentUsageEntryDto> entries;
  final String? nextCursor;
}

class AgentUsageGroupDto {
  AgentUsageGroupDto({this.providerId, this.modelId, this.role, required this.requestCount,
    required this.reportedRequests, required this.estimatedRequests, required this.unknownRequests,
    required this.input, required this.output, required this.cached, required this.total});
  final String? providerId;
  final String? modelId;
  final String? role;
  final int requestCount;
  final int reportedRequests;
  final int estimatedRequests;
  final int unknownRequests;
  final AgentUsageMetricDto input;
  final AgentUsageMetricDto output;
  final AgentUsageMetricDto cached;
  final AgentUsageMetricDto total;
}

class AgentUsageGroupsPageDto {
  AgentUsageGroupsPageDto({required this.owner, this.taskId, this.from, this.until, required this.groupBy,
    required this.requestCount, required this.groupCount, required this.undatedRequests,
    required this.groups, this.nextCursor});
  final String owner;
  final String? taskId;
  final int? from;
  final int? until;
  final String groupBy;
  final int requestCount;
  final int groupCount;
  final int undatedRequests;
  final List<AgentUsageGroupDto> groups;
  final String? nextCursor;
}

class ConversationDetailDto {
  ConversationDetailDto({
    required this.id,
    required this.title,
    required this.profileId,
    required this.studyMode,
    required this.nodes,
    required this.isGenerating,
    required this.lifecycleRevision,
    this.draftText,
    this.activeModelId,
    this.activeModelName,
  });
  final String id;
  final String title;
  final String profileId;
  final String studyMode;
  final List<MessageNodeDto> nodes;
  final bool isGenerating;
  final int lifecycleRevision;
  final String? draftText;
  final String? activeModelId;
  final String? activeModelName;
}

class SendMessageRequest {
  SendMessageRequest({
    required this.requestId,
    required this.profileId,
    required this.contextEpoch,
    required this.text,
    required this.studyMode,
    this.conversationId,
    this.modelId,
    this.attachmentPaths,
  });
  final String requestId;
  final String profileId;
  final int contextEpoch;
  final String text;
  final String studyMode;
  final String? conversationId;
  final String? modelId;
  final List<String>? attachmentPaths;
}

class MaterialFileDto {
  MaterialFileDto({
    required this.id,
    required this.profileId,
    required this.name,
    required this.extension,
    required this.state,
    required this.detail,
    required this.size,
    required this.sha256,
    required this.createdAt,
    this.pageNumber,
    this.symbolCorrection,
    required this.symbolCheckPending,
  });
  final String id;
  final String profileId;
  final String name;
  final String extension;
  final String state;
  final String detail;
  final int size;
  final String sha256;
  final int createdAt;
  final int? pageNumber;
  final String? symbolCorrection;
  final bool symbolCheckPending;
}

class MemoryItemDto {
  MemoryItemDto({
    required this.id,
    required this.type,
    required this.content,
    required this.sourceTopic,
    required this.sourceFact,
    required this.evidenceType,
    required this.status,
    required this.revision,
    required this.createdAt,
    required this.updatedAt,
    this.sourceQuote,
    this.sourceConversationId,
  });
  final String id;
  final String type;
  final String content;
  final String sourceTopic;
  final String sourceFact;
  final String evidenceType;
  final String status;
  final int revision;
  final int createdAt;
  final int updatedAt;
  final String? sourceQuote;
  final String? sourceConversationId;
}

class ArtifactDto {
  ArtifactDto({
    required this.id,
    required this.name,
    required this.extension,
    required this.state,
    required this.size,
    required this.sha256,
    required this.createdAt,
    this.previewContent,
    this.statusDetail,
    this.previewCoverage,
  });
  final String id;
  final String name;
  final String extension;
  final String state;
  final int size;
  final String sha256;
  final int createdAt;
  final String? previewContent;
  final String? statusDetail;
  final String? previewCoverage;
}

class DiagnosticsDto {
  DiagnosticsDto({
    required this.deviceModel,
    required this.osVersion,
    required this.appVersion,
    required this.recentCrashCount,
    required this.databaseRevision,
    required this.logSummary,
  });
  final String deviceModel;
  final String osVersion;
  final String appVersion;
  final int recentCrashCount;
  final int databaseRevision;
  final String logSummary;
}

class SkillDto {
  SkillDto({required this.name, required this.description, required this.builtin, required this.enabled, required this.state, required this.detail, required this.files, this.compatibility, this.license, this.allowedTools});
  final String name;
  final String description;
  final bool builtin;
  final bool enabled;
  final String state;
  final String detail;
  final List<String> files;
  final String? compatibility;
  final String? license;
  final String? allowedTools;
}

class AgentUsageExportDto {
  AgentUsageExportDto({required this.owner,this.taskId,this.from,this.until,required this.groupBy,
    required this.requestId,required this.artifactId,required this.sha256,required this.size,
    required this.sourceSha256,required this.capturedAt,required this.requestCount,required this.recordCount,required this.reused});
  final String owner;
  final String? taskId;
  final int? from;
  final int? until;
  final String groupBy;
  final String requestId;
  final String artifactId;
  final String sha256;
  final int size;
  final String sourceSha256;
  final int capturedAt;
  final int requestCount;
  final int recordCount;
  final bool reused;
}

class AgentUsageExportIntentDto {
  AgentUsageExportIntentDto({required this.owner,this.taskId,this.from,this.until,required this.groupBy,
    required this.requestId,required this.artifactId,required this.sha256,required this.size,required this.createdAt});
  final String owner;
  final String? taskId;
  final int? from;
  final int? until;
  final String groupBy;
  final String requestId;
  final String artifactId;
  final String sha256;
  final int size;
  final int createdAt;
}

class AgentUsageExportHistoryPageDto {
  AgentUsageExportHistoryPageDto({required this.owner,required this.entries,this.nextCursor});
  final String owner;
  final List<AgentUsageExportIntentDto> entries;
  final String? nextCursor;
}

class AgentUsageAlertsDto {
  AgentUsageAlertsDto({required this.owner, this.taskId, this.from, this.until,
    required this.evaluatedAt, required this.uncertainRequests, required this.unreportedCompletedRequests,
    required this.denseTaskCount, required this.denseRequests, required this.undatedRequests});
  final String owner;
  final String? taskId;
  final int? from;
  final int? until;
  final int evaluatedAt;
  final int uncertainRequests;
  final int unreportedCompletedRequests;
  final int denseTaskCount;
  final int denseRequests;
  final int undatedRequests;
}

@HostApi()
abstract class StudyHostApi {
  @async
  List<ConversationSummaryDto> searchConversations(String profileId, String query, int limit, int offset, bool archived);

  @async
  SimpleCommandResult editMessage(String profileId, String conversationId, String messageId, String text, String requestId, int expectedEpoch);

  @async
  String getSurfaceState(String profileId, String conversationId, String messageId, String toolCallId, String revision);

  @async
  SimpleCommandResult surfaceAction(String profileId, String conversationId, String messageId, String toolCallId, String revision, String stateJson, String? actionId, String requestId, int expectedEpoch);

  @async
  SimpleCommandResult watchConversation(String profileId, String? conversationId, int expectedEpoch);

  @async
  List<String> pickAttachments(String profileId, int expectedEpoch);

  @async
  SimpleCommandResult renameConversation(String profileId, String conversationId, String title);

  @async
  SimpleCommandResult deleteConversation(String profileId, String conversationId);

  @async
  SimpleCommandResult setConversationPinned(String profileId, String conversationId, bool pinned, int expectedEpoch);

  @async
  SimpleCommandResult setConversationArchived(String profileId, String conversationId, bool archived, int expectedEpoch);

  @async
  SimpleCommandResult openNativePage(String page);

  @async
  List<SkillDto> getSkills(String profileId);

  @async
  SimpleCommandResult setSkillEnabled(String profileId, String name, bool enabled, int expectedEpoch);

  @async
  SimpleCommandResult documentTaskAction(String profileId, String conversationId, String messageId, String toolCallId, String action, int expectedEpoch);

  @async
  StudyBootstrapDto getBootstrap(String profileId, int contextEpoch);

  @async
  SimpleCommandResult switchProfile(String profileId, int expectedEpoch);

  @async
  List<ConversationSummaryDto> getConversations(String profileId, int limit, int offset, bool archived);

  @async
  ConversationDetailDto getConversationDetails(String profileId, String conversationId);

  @async
  AgentTaskDto? getConversationTask(String profileId, String conversationId, int expectedEpoch);
  @async
  AgentCatalogPageDto getAgentTaskCatalog(String profileId, String kind, String? cursor, int limit, int expectedEpoch);
  @async
  AgentCatalogDetailDto getAgentTaskDetail(String profileId, String taskId, String? cursor, int limit, int expectedEpoch);
  @async
  AgentUsagePageDto getAgentUsage(String profileId, String? taskId, int? from, int? until,
      String? cursor, int limit, int expectedEpoch);
  @async
  AgentUsageGroupsPageDto getAgentUsageGroups(String profileId, String? taskId, int? from, int? until,
      String groupBy, String? cursor, int limit, int expectedEpoch);
  @async
  AgentUsageExportDto exportAgentUsage(String profileId, String? taskId, int? from, int? until,
      String groupBy, String requestId, int expectedEpoch);
  @async
  AgentUsageExportIntentDto? getAgentUsageLastExport(String profileId, String? taskId, int? from, int? until,
      String groupBy, int expectedEpoch);
  @async
  AgentUsageExportHistoryPageDto getAgentUsageExportHistory(String profileId, String? cursor, int limit, int expectedEpoch);
  @async
  AgentUsageExportDto verifyAgentUsageExport(String profileId, String? taskId, int? from, int? until,
      String groupBy, String requestId, String expectedSha256, int expectedEpoch);

  @async
  SimpleCommandResult taskCommand(String profileId, String conversationId, String taskId,
      String requestId, int expectedEpoch, int expectedRevision, int expectedFence,
      String action, String payload);

  @async
  SimpleCommandResult taskSafetyCommand(String profileId, String taskId, String requestId,
      int expectedEpoch, int expectedRevision, int expectedFence, String action);

  @async
  SimpleCommandResult updateTaskCredential(String profileId, String conversationId, String taskId,
      String requestId, int expectedEpoch, int expectedRevision, int expectedFence,
      String apiKey);

  @async
  SimpleCommandResult continueTaskCredential(String profileId, String conversationId, String taskId,
      String requestId, int expectedEpoch);

  @async
  SimpleCommandResult sendMessage(SendMessageRequest request);

  @async
  SimpleCommandResult stopGeneration(String profileId, String conversationId, String requestId);

  @async
  SimpleCommandResult retryMessage(String profileId, String conversationId, String nodeId, String requestId);

  @async
  SimpleCommandResult switchBranch(String profileId, String conversationId, String nodeId, int branchIndex);

  @async
  SimpleCommandResult setStudyMode(String profileId, String conversationId, String studyMode);

  @async
  SimpleCommandResult approveToolCall(String profileId, String conversationId, String toolCallId, bool approved, String requestId);

  @async
  List<MaterialFileDto> getMaterials(String profileId, String filter);

  @async
  SimpleCommandResult checkSymbol(String profileId, String materialId, String chosenSymbol, String requestId);

  @async
  List<ProjectDto> getProjects(String profileId);
  @async
  SimpleCommandResult saveProject(String profileId, String? projectId, String name, int expectedEpoch);
  @async
  SimpleCommandResult deleteProject(String profileId, String projectId, int expectedEpoch);
  @async
  SimpleCommandResult moveConversationToProject(String profileId, String conversationId, String? projectId, int expectedEpoch);
  @async
  SimpleCommandResult createProjectChat(String profileId, String projectId, int expectedEpoch);
  @async
  SimpleCommandResult addMemory(String profileId, String content, String type, String requestId, int expectedEpoch);
  @async
  List<MemoryItemDto> getMemories(String profileId, String filter);

  @async
  SimpleCommandResult updateMemory(String profileId, String memoryId, String newContent, int expectedRevision, String requestId);

  @async
  SimpleCommandResult deleteMemory(String profileId, String memoryId, String requestId);

  @async
  SimpleCommandResult toggleAutoMemory(String profileId, bool enabled);

  @async
  List<ArtifactDto> getArtifacts(String profileId);

  @async
  ArtifactDto getArtifactPreview(String profileId, String artifactId, int expectedEpoch);

  @async
  SimpleCommandResult openArtifact(String profileId, String artifactId);

  @async
  SimpleCommandResult saveArtifact(String profileId, String artifactId);

  @async
  SimpleCommandResult shareArtifact(String profileId, String artifactId);

  @async
  List<ProviderDto> getProviders();

  @async
  SimpleCommandResult saveProvider(ProviderDto provider);

  @async
  SimpleCommandResult testProvider(String providerId);

  @async
  SimpleCommandResult selectActiveModel(String providerId, String modelId);

  @async
  DiagnosticsDto getDiagnostics();

  @async
  AgentUsageAlertsDto getAgentUsageAlerts(String profileId, String? taskId, int? from, int? until, int expectedEpoch);

  @async
  SimpleCommandResult exportDiagnostics();
}
