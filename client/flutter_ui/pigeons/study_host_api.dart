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
  SimpleCommandResult exportDiagnostics();
}
