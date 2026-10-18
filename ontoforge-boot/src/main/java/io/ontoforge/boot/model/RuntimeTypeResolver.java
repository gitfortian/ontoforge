package io.ontoforge.boot.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

import static io.ontoforge.boot.model.NativeDraftService.DraftFailure;

/**
 * Read-only projection of validated Native candidates. This is deliberately a resolver,
 * not a second persisted registry: PostgreSQL drafts and verified local snapshots remain
 * the authoritative sources.
 */
@Service
public class RuntimeTypeResolver {
    private static final Pattern OBJECT_TYPE_ID = Pattern.compile("^[a-z][a-z0-9-]{1,63}$");

    private final NativeDraftService drafts;
    private final NativeSnapshotService snapshots;
    private final ObjectMapper mapper;

    public RuntimeTypeResolver(NativeDraftService drafts, NativeSnapshotService snapshots, ObjectMapper mapper) {
        this.drafts = drafts;
        this.snapshots = snapshots;
        this.mapper = mapper;
    }

    public record DraftTypeSourceRef(String kind, String draftId, long revision) {}
    public record SnapshotTypeSourceRef(String kind, String snapshotId) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TypeSummary(String objectTypeId, String apiName, String label,
                              String primaryKeyRef, int propertyCount) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PropertyDescriptor(String id, String apiName, String label, String valueType,
                                     boolean required, boolean searchable, String classification,
                                     List<String> enumValues) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TypeCatalog(Object sourceRef, String packageId, long modelSourceRevision,
                              String lifecycle, List<TypeSummary> items) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TypeDescriptor(Object sourceRef, String packageId, long modelSourceRevision,
                                 String lifecycle, String objectTypeId, String apiName, String label,
                                 String primaryKeyRef, List<PropertyDescriptor> properties,
                                 String payloadSha256) {}

    private record Resolved(Object sourceRef, String packageId, long revision, String lifecycle,
                            JsonNode candidate, String payloadSha256) {}

    public TypeCatalog listDraftTypes(UUID draftId, long revision) {
        return catalog(resolveDraft(draftId, revision));
    }

    public TypeDescriptor getDraftType(UUID draftId, long revision, String objectTypeId) {
        validateObjectTypeId(objectTypeId);
        return descriptor(resolveDraft(draftId, revision), objectTypeId);
    }

    public TypeCatalog listSnapshotTypes(UUID snapshotId) {
        return catalog(resolveSnapshot(snapshotId));
    }

    public TypeDescriptor getSnapshotType(UUID snapshotId, String objectTypeId) {
        validateObjectTypeId(objectTypeId);
        return descriptor(resolveSnapshot(snapshotId), objectTypeId);
    }

    private Resolved resolveDraft(UUID draftId, long requestedRevision) {
        if (requestedRevision < 1) {
            throw fail(400, "MALFORMED_TYPE_REF", "revision 必须是正整数");
        }
        NativeDraftService.DraftDetail draft;
        try {
            // DraftDetail is read from one SELECT; revision, editing document and validation
            // report therefore describe the same PostgreSQL read point.
            draft = drafts.get(draftId);
        } catch (DraftFailure e) {
            if ("DRAFT_NOT_FOUND".equals(e.code)) {
                throw fail(404, "TYPE_SOURCE_NOT_FOUND", "未找到指定草稿来源");
            }
            throw e;
        }
        if (draft.revision() != requestedRevision) {
            throw fail(409, "TYPE_SOURCE_REVISION_CONFLICT",
                    "草稿版本已变化，请重新读取当前修订并校验");
        }
        JsonNode report = draft.validation();
        JsonNode validatedRevision = report.path("validatedRevision");
        if (!"VALID".equals(report.path("status").asText())
                || !validatedRevision.isIntegralNumber()
                || validatedRevision.asLong(-1) != requestedRevision) {
            throw fail(409, "TYPE_SOURCE_NOT_VALIDATED",
                    "只有当前 revision 已通过完整校验的草稿才能解析运行时类型");
        }
        DraftValidation.Result result = DraftValidation.validate(draft.packageId(), draft.editingContent(), mapper);
        JsonNode candidate = result.nativeCandidate();
        if (!"VALID".equals(result.status()) || candidate == null
                || !NativePackageSchema.validate(candidate).isEmpty()) {
            throw fail(422, "UNSUPPORTED_TYPE_DEFINITION",
                    "当前草稿无法重新生成符合 Native V0.2 合同的类型定义，请回到建模工作台重新校验");
        }
        return new Resolved(new DraftTypeSourceRef("VALIDATED_DRAFT", draftId.toString(), requestedRevision),
                draft.packageId(), requestedRevision, "VALIDATED_DRAFT_ONLY", candidate, null);
    }

    private Resolved resolveSnapshot(UUID snapshotId) {
        NativeSnapshotService.Snapshot snapshot;
        try {
            // get() verifies canonical bytes, SHA-256, JSONB mirror, Native Schema and package identity.
            snapshot = snapshots.get(snapshotId);
        } catch (DraftFailure e) {
            if ("SNAPSHOT_NOT_FOUND".equals(e.code)) {
                throw fail(404, "TYPE_SOURCE_NOT_FOUND", "未找到指定本地快照来源");
            }
            throw e;
        }
        JsonNode candidate = snapshot.snapshotContent();
        if (candidate == null || !NativePackageSchema.validate(candidate).isEmpty()
                || !snapshot.packageId().equals(candidate.path("packageId").asText())) {
            throw fail(500, "SNAPSHOT_INTEGRITY_FAILURE", "快照类型定义无法通过完整性核验，拒绝继续解析");
        }
        return new Resolved(new SnapshotTypeSourceRef("LOCAL_SNAPSHOT", snapshotId.toString()),
                snapshot.packageId(), snapshot.sourceRevision(), "LOCAL_SNAPSHOT_ONLY",
                candidate, snapshot.payloadSha256());
    }

    private TypeCatalog catalog(Resolved source) {
        JsonNode objects = source.candidate().path("objectTypes");
        if (!objects.isArray() || objects.size() > 50) {
            throw fail(422, "UNSUPPORTED_TYPE_DEFINITION", "Native Candidate 的 ObjectType 列表不符合当前边界");
        }
        List<JsonNode> ordered = new ArrayList<>();
        objects.forEach(ordered::add);
        ordered.sort(Comparator.comparing(n -> n.path("id").asText("")));
        List<TypeSummary> items = new ArrayList<>();
        for (JsonNode object : ordered) {
            String id = requiredText(object, "id");
            String apiName = requiredText(object, "apiName");
            items.add(new TypeSummary(id, apiName, optionalText(object, "label"),
                    requiredText(object, "primaryKeyRef"), object.path("properties").size()));
        }
        return new TypeCatalog(source.sourceRef(), source.packageId(), source.revision(),
                source.lifecycle(), List.copyOf(items));
    }

    private TypeDescriptor descriptor(Resolved source, String objectTypeId) {
        JsonNode objects = source.candidate().path("objectTypes");
        if (!objects.isArray()) {
            throw fail(422, "UNSUPPORTED_TYPE_DEFINITION", "Native Candidate 缺少 ObjectType 列表");
        }
        JsonNode found = null;
        for (JsonNode object : objects) {
            if (objectTypeId.equals(object.path("id").asText())) {
                found = object;
                break;
            }
        }
        if (found == null) {
            throw fail(404, "OBJECT_TYPE_NOT_FOUND", "该模型来源中不存在指定 ObjectType");
        }
        JsonNode properties = found.path("properties");
        if (!properties.isArray() || properties.size() > 100) {
            throw fail(422, "UNSUPPORTED_TYPE_DEFINITION", "ObjectType 的 Property 列表不符合当前边界");
        }
        List<PropertyDescriptor> descriptors = new ArrayList<>();
        for (JsonNode property : properties) {
            descriptors.add(property(property));
        }
        return new TypeDescriptor(source.sourceRef(), source.packageId(), source.revision(), source.lifecycle(),
                objectTypeId, requiredText(found, "apiName"), optionalText(found, "label"),
                requiredText(found, "primaryKeyRef"), List.copyOf(descriptors), source.payloadSha256());
    }

    private PropertyDescriptor property(JsonNode property) {
        String id = requiredText(property, "id");
        String apiName = requiredText(property, "apiName");
        String valueType = requiredText(property, "valueType");
        String classification = requiredText(property, "classification");
        JsonNode required = property.get("required");
        JsonNode searchable = property.get("searchable");
        if (required == null || !required.isBoolean() || searchable == null || !searchable.isBoolean()) {
            throw fail(422, "UNSUPPORTED_TYPE_DEFINITION", "Property 缺少必填性或可检索性布尔合同");
        }
        List<String> enumValues = null;
        JsonNode values = property.get("enumValues");
        if (values != null && !values.isNull()) {
            if (!values.isArray()) {
                throw fail(422, "UNSUPPORTED_TYPE_DEFINITION", "Property 的 enumValues 必须是数组");
            }
            enumValues = new ArrayList<>();
            for (JsonNode value : values) {
                if (!value.isTextual()) {
                    throw fail(422, "UNSUPPORTED_TYPE_DEFINITION", "枚举值必须是字符串");
                }
                enumValues.add(value.asText());
            }
            enumValues = List.copyOf(enumValues);
        }
        return new PropertyDescriptor(id, apiName, optionalText(property, "label"), valueType,
                required.booleanValue(), searchable.booleanValue(), classification, enumValues);
    }

    private static void validateObjectTypeId(String id) {
        if (id == null || !OBJECT_TYPE_ID.matcher(id).matches()) {
            throw fail(400, "MALFORMED_TYPE_REF", "objectTypeId 格式不合法");
        }
    }

    private static String requiredText(JsonNode node, String key) {
        JsonNode value = node.get(key);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw fail(422, "UNSUPPORTED_TYPE_DEFINITION", "Native 类型缺少有效字段：" + key);
        }
        return value.asText();
    }

    private static String optionalText(JsonNode node, String key) {
        JsonNode value = node.get(key);
        return value != null && value.isTextual() && !value.asText().isBlank() ? value.asText() : null;
    }

    private static DraftFailure fail(int status, String code, String message) {
        return new DraftFailure(status, code, message);
    }
}
