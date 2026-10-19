package io.ontoforge.core.compiler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Bounded editor input != a valid/approved/published Native V0.2 package. */
public final class DraftValidation {
    private DraftValidation() {}
    public static final String EDITOR_VERSION = "0.1.0";
    public static final String NATIVE_VERSION = "0.2.0";
    private static final Pattern PACKAGE_ID = Pattern.compile("^[a-z][a-z0-9-]*(\\.[a-z][a-z0-9-]*)+$");
    private static final Pattern DEFINITION_ID = Pattern.compile("^[a-z][a-z0-9-]{1,63}$");
    private static final Pattern API_NAME = Pattern.compile("^[A-Za-z][A-Za-z0-9]{1,80}$");
    private static final Set<String> TYPES = Set.of("STRING", "INTEGER", "DECIMAL", "BOOLEAN", "DATETIME", "ENUM");
    private static final Set<String> CLASSIFICATIONS = Set.of("PUBLIC", "INTERNAL", "CONFIDENTIAL", "RESTRICTED");
    private static final Set<String> ROOT = Set.of("editorSchemaVersion", "objectTypes", "linkTypes");
    private static final Set<String> OBJECT = Set.of("id", "apiName", "label", "description", "primaryKeyRef", "properties");
    private static final Set<String> PROPERTY = Set.of("id", "apiName", "label", "valueType", "required", "searchable", "classification", "enumValues");
    private static final Set<String> LINK = Set.of("id","apiName","label","sourceTypeRef","targetTypeRef","cardinality","evidenceRequired");
    private static final Set<String> CARDINALITY = Set.of("ONE_TO_ONE","ONE_TO_MANY","MANY_TO_ONE","MANY_TO_MANY");

    public record Issue(String path, String code, String message) {}
    public record Result(String status, List<Issue> issues, JsonNode nativeCandidate) {}

    public static boolean packageIdValid(String id) {
        return id != null && id.length() <= 160 && PACKAGE_ID.matcher(id).matches();
    }

    public static JsonNode initialDocument(ObjectMapper mapper) {
        ObjectNode doc = mapper.createObjectNode();
        doc.put("editorSchemaVersion", EDITOR_VERSION);
        doc.putArray("objectTypes");
        doc.putArray("linkTypes");
        return doc;
    }

    /** Reject unsafe JSON shapes before saving WIP, while allowing missing business values. */
    public static List<Issue> checkEditor(JsonNode doc, ObjectMapper mapper) {
        List<Issue> issues = new ArrayList<>();
        if (doc == null || !doc.isObject()) {
            issues.add(new Issue("", "EXPECTED_OBJECT", "编辑文档必须是 JSON 对象"));
            return issues;
        }
        if (mapperBytes(mapper, doc) > 262144) {
            issues.add(new Issue("", "PAYLOAD_TOO_LARGE", "编辑文档不能超过 256 KiB"));
            return issues;
        }
        unknown(doc, ROOT, "", issues);
        if (!EDITOR_VERSION.equals(text(doc.path("editorSchemaVersion"))))
            issues.add(new Issue("/editorSchemaVersion", "UNSUPPORTED_EDITOR_SCHEMA", "不支持的编辑文档版本"));
        JsonNode objects = doc.get("objectTypes");
        JsonNode links = doc.get("linkTypes");
        if (objects == null || !objects.isArray()) {
            issues.add(new Issue("/objectTypes", "EXPECTED_ARRAY", "对象类型必须是数组"));
            return issues;
        }
        if (links == null || !links.isArray()) {
            issues.add(new Issue("/linkTypes","EXPECTED_ARRAY","关系类型必须是数组"));
        } else {
            if(links.size()>200)issues.add(new Issue("/linkTypes","LIMIT_EXCEEDED","最多允许 200 种关系类型"));
            for(int i=0;i<links.size()&&i<201;i++){
                JsonNode link=links.get(i);
                String path="/linkTypes/"+i;
                if(!link.isObject()){issues.add(new Issue(path,"EXPECTED_OBJECT","关系必须是对象"));continue;}
                unknown(link,LINK,path,issues);
                boundedString(link,"id",64,path,issues);
                boundedString(link,"apiName",81,path,issues);
                boundedString(link,"label",150,path,issues);
                boundedString(link,"sourceTypeRef",64,path,issues);
                boundedString(link,"targetTypeRef",64,path,issues);
                boundedString(link,"cardinality",24,path,issues);
                bool(link,"evidenceRequired",path,issues);
            }
        }
        if (objects.size() > 50) issues.add(new Issue("/objectTypes", "LIMIT_EXCEEDED", "最多 50 个对象类型"));
        int totalProperties = 0;
        for (int i = 0; i < objects.size() && i < 51; i++) {
            JsonNode obj = objects.get(i);
            String path = "/objectTypes/" + i;
            if (!obj.isObject()) {issues.add(new Issue(path, "EXPECTED_OBJECT", "对象类型必须是对象")); continue;}
            unknown(obj, OBJECT, path, issues);
            boundedString(obj, "id", 64, path, issues);
            boundedString(obj, "apiName", 81, path, issues);
            boundedString(obj, "label", 150, path, issues);
            boundedString(obj, "description", 500, path, issues);
            boundedString(obj, "primaryKeyRef", 64, path, issues);
            JsonNode props = obj.get("properties");
            if (props == null || !props.isArray()) {
                issues.add(new Issue(path + "/properties", "EXPECTED_ARRAY", "属性必须是数组"));
                continue;
            }
            totalProperties += props.size();
            if (props.size() > 100) issues.add(new Issue(path + "/properties", "LIMIT_EXCEEDED", "单个类型最多 100 个属性"));
            for (int j = 0; j < props.size() && j < 101; j++) {
                String base = path + "/properties/" + j;
                JsonNode prop = props.get(j);
                if (!prop.isObject()) {issues.add(new Issue(base, "EXPECTED_OBJECT", "属性必须是对象")); continue;}
                unknown(prop, PROPERTY, base, issues);
                boundedString(prop, "id", 64, base, issues);
                boundedString(prop, "apiName", 81, base, issues);
                boundedString(prop, "label", 150, base, issues);
                boundedString(prop, "valueType", 24, base, issues);
                boundedString(prop, "classification", 24, base, issues);
                bool(prop, "required", base, issues);
                bool(prop, "searchable", base, issues);
                JsonNode enums = prop.get("enumValues");
                if (enums != null && !enums.isNull()) {
                    if (!enums.isArray() || enums.size() > 100) {
                        issues.add(new Issue(base + "/enumValues", "INVALID_ARRAY", "枚举值必须是最多 100 项的数组"));
                    } else for (int k = 0; k < enums.size(); k++) {
                        if (!enums.get(k).isTextual() || enums.get(k).textValue().length() > 150)
                            issues.add(new Issue(base + "/enumValues/" + k, "INVALID_STRING", "枚举项必须是有效的有限长度字符串"));
                    }
                }
            }
        }
        if (totalProperties > 1000) issues.add(new Issue("/objectTypes", "LIMIT_EXCEEDED", "一个模型包最多 1000 个属性"));
        return issues;
    }

    private static boolean optionalMissing(JsonNode v) {
        return v==null || v.isNull() || (v.isTextual() && v.textValue().isBlank());
    }
    private static int mapperBytes(ObjectMapper mapper, JsonNode doc) {
        try {return mapper.writeValueAsBytes(doc).length;}
        catch (Exception e) {return Integer.MAX_VALUE;}
    }
    private static void unknown(JsonNode n, Set<String> allowed, String path, List<Issue> out) {
        n.fieldNames().forEachRemaining(name -> {
            if (!allowed.contains(name)) out.add(new Issue(path + "/" + name, "UNKNOWN_FIELD", "当前编辑合同不允许该字段"));
        });
    }
    private static void boundedString(JsonNode n, String key, int max, String path, List<Issue> out) {
        JsonNode v = n.get(key);
        if (v != null && !v.isNull() && (!v.isTextual() || v.textValue().length() > max))
            out.add(new Issue(path + "/" + key, "INVALID_STRING", "请输入符合长度限制的文本"));
    }
    private static void bool(JsonNode n, String key, String path, List<Issue> out) {
        JsonNode v = n.get(key);
        if (v != null && !v.isBoolean()) out.add(new Issue(path + "/" + key, "EXPECTED_BOOLEAN", "必须填写 true 或 false"));
    }
    private static String text(JsonNode node) {return node != null && node.isTextual() ? node.textValue() : null;}
    private static String get(JsonNode node, String key) {return text(node.path(key));}

    /** S0-0.4 supports complete same-package ObjectType and LinkType definitions. */
    public static Result validate(String packageId, JsonNode doc, ObjectMapper mapper) {
        List<Issue> issues = new ArrayList<>();
        List<Issue> malformed = checkEditor(doc, mapper);
        if (!malformed.isEmpty()) return new Result("INVALID", malformed, null);
        boolean invalid = false;
        ArrayNode objects = (ArrayNode) doc.get("objectTypes");
        if (objects.isEmpty()) issues.add(new Issue("/objectTypes", "REQUIRED", "至少建立一个完整对象类型"));
        Set<String> objectIds = new HashSet<>(), objectApis = new HashSet<>();
        for (int i = 0; i < objects.size(); i++) {
            JsonNode obj = objects.get(i);
            String path = "/objectTypes/" + i;
            String id = get(obj, "id"), api = get(obj, "apiName");
            if (id == null || id.isBlank()) issues.add(new Issue(path + "/id", "REQUIRED", "请填写对象类型 ID"));
            else if (!DEFINITION_ID.matcher(id).matches()) {issues.add(new Issue(path + "/id", "INVALID_ID", "对象类型 ID 格式错误"));invalid=true;}
            if (id != null && !objectIds.add(id)) {issues.add(new Issue(path + "/id", "DUPLICATE_ID", "模型包中的对象类型 ID 重复"));invalid=true;}
            if (api == null || api.isBlank()) issues.add(new Issue(path + "/apiName", "REQUIRED", "请填写 API 名称"));
            else if (!API_NAME.matcher(api).matches()) {issues.add(new Issue(path + "/apiName", "INVALID_API_NAME", "API 名格式错误"));invalid=true;}
            if (api != null && !objectApis.add(api)) {issues.add(new Issue(path + "/apiName", "DUPLICATE_API_NAME", "模型包中的 API 名称重复"));invalid=true;}
            String label=get(obj,"label");
            // Blank optional display labels in a WIP mean "not supplied", not a published value.
            JsonNode props = obj.get("properties");
            if (props == null || !props.isArray() || props.isEmpty()) {
                issues.add(new Issue(path + "/properties", "REQUIRED", "至少定义一个属性"));continue;
            }
            Set<String> propertyIds = new HashSet<>(), propertyApis=new HashSet<>();
            for (int j=0; j<props.size(); j++) {
                JsonNode p=props.get(j);
                String pp=path+"/properties/"+j;
                String pid=get(p,"id"), pa=get(p,"apiName");
                if (pid==null || pid.isBlank()) issues.add(new Issue(pp+"/id","REQUIRED","请填写属性 ID"));
                else if (!DEFINITION_ID.matcher(pid).matches()) {issues.add(new Issue(pp+"/id","INVALID_ID","属性 ID 格式错误"));invalid=true;}
                if (pid!=null && !propertyIds.add(pid)) {issues.add(new Issue(pp+"/id","DUPLICATE_ID","当前对象类型内属性 ID 重复"));invalid=true;}
                if (pa==null || pa.isBlank()) issues.add(new Issue(pp+"/apiName","REQUIRED","请填写属性 API 名"));
                else if (!API_NAME.matcher(pa).matches()) {issues.add(new Issue(pp+"/apiName","INVALID_API_NAME","属性 API 名格式错误"));invalid=true;}
                if (pa!=null && !propertyApis.add(pa)) {issues.add(new Issue(pp+"/apiName","DUPLICATE_API_NAME","当前对象类型内属性 API 名重复"));invalid=true;}
                String pl=get(p,"label");
                // WIP optional display labels are removed from the complete Candidate when empty.
                String type=get(p,"valueType");
                if (type==null || type.isBlank()) issues.add(new Issue(pp+"/valueType","REQUIRED","请选择字段类型"));
                else if (!TYPES.contains(type)) {issues.add(new Issue(pp+"/valueType","UNKNOWN_TYPE","不支持的字段类型"));invalid=true;}
                String classification=get(p,"classification");
                if(classification==null || classification.isBlank()) issues.add(new Issue(pp+"/classification","REQUIRED","请选择数据分类"));
                else if (!CLASSIFICATIONS.contains(classification)) {issues.add(new Issue(pp+"/classification","UNKNOWN_CLASSIFICATION","不支持的数据分类"));invalid=true;}
                if(!p.has("required")) issues.add(new Issue(pp+"/required","REQUIRED","请选择是否必填"));
                if(!p.has("searchable")) issues.add(new Issue(pp+"/searchable","REQUIRED","请选择是否可搜索"));
                JsonNode enums=p.get("enumValues");
                if("ENUM".equals(type)) {
                    if(enums==null || !enums.isArray() || enums.isEmpty()) issues.add(new Issue(pp+"/enumValues","REQUIRED","ENUM 至少一个枚举值"));
                    else {
                        Set<String> values=new HashSet<>();
                        for(JsonNode v:enums) {
                            if(v.textValue().isBlank() || !values.add(v.textValue())) {
                                issues.add(new Issue(pp+"/enumValues","INVALID_ENUM","ENUM 值不能为空或重复"));invalid=true;
                            }
                        }
                    }
                } else if(enums!=null && !enums.isNull() && (!enums.isArray() || !enums.isEmpty())) {
                    issues.add(new Issue(pp+"/enumValues","UNEXPECTED_ENUM","非 ENUM 属性不允许有枚举值"));invalid=true;
                }
            }
            String pk=get(obj,"primaryKeyRef");
            if(pk==null || pk.isBlank()) issues.add(new Issue(path+"/primaryKeyRef","REQUIRED","请选择主键属性"));
            else {
                JsonNode matched=null;
                for(JsonNode p:props) if(pk.equals(get(p,"id"))) {matched=p;break;}
                if(matched==null){issues.add(new Issue(path+"/primaryKeyRef","PK_NOT_FOUND","主键必须引用本类型内的属性"));invalid=true;}
                else {
                    if(!matched.path("required").asBoolean(false)){issues.add(new Issue(path+"/primaryKeyRef","PK_REQUIRED","主键属性必须是必填"));invalid=true;}
                    if(!"STRING".equals(get(matched,"valueType")) && !"INTEGER".equals(get(matched,"valueType"))){issues.add(new Issue(path+"/primaryKeyRef","PK_TYPE","主键只允许 STRING 或 INTEGER"));invalid=true;}
                }
            }
        }
        ArrayNode links=(ArrayNode)doc.get("linkTypes");
        Set<String> linkIds=new HashSet<>(),linkApis=new HashSet<>();
        for(int i=0;i<links.size();i++){
            JsonNode link=links.get(i);
            String path="/linkTypes/"+i;
            String id=get(link,"id"),api=get(link,"apiName");
            if(id==null||id.isBlank())issues.add(new Issue(path+"/id","LINK_ID_REQUIRED","请填写关系 ID"));
            else if(!DEFINITION_ID.matcher(id).matches()){issues.add(new Issue(path+"/id","INVALID_ID","关系 ID 格式错误"));invalid=true;}
            if(id!=null&&!id.isBlank()&&!linkIds.add(id)){issues.add(new Issue(path+"/id","DUPLICATE_LINK_ID","同包关系 ID 重复"));invalid=true;}
            if(api==null||api.isBlank())issues.add(new Issue(path+"/apiName","REQUIRED","请填写关系 API 名"));
            else if(!API_NAME.matcher(api).matches()){issues.add(new Issue(path+"/apiName","INVALID_API_NAME","关系 API 名格式错误"));invalid=true;}
            if(api!=null&&!api.isBlank()&&!linkApis.add(api)){issues.add(new Issue(path+"/apiName","DUPLICATE_LINK_API_NAME","同包关系 API 名重复"));invalid=true;}
            for(String key:List.of("sourceTypeRef","targetTypeRef")){
                String ref=get(link,key);
                if(ref==null||ref.isBlank())issues.add(new Issue(path+"/"+key,
                    "sourceTypeRef".equals(key)?"LINK_SOURCE_REQUIRED":"LINK_TARGET_REQUIRED",
                    "请先选择关联对象"));
                else if(!objectIds.contains(ref)){issues.add(new Issue(path+"/"+key,
                    "sourceTypeRef".equals(key)?"LINK_SOURCE_UNKNOWN":"LINK_TARGET_UNKNOWN",
                    "关联对象在当前包中不存在，必须人工修复"));invalid=true;}
            }
            String cardinality=get(link,"cardinality");
            if(cardinality==null||cardinality.isBlank())
                issues.add(new Issue(path+"/cardinality","LINK_CARDINALITY_REQUIRED","请选择关联基数"));
            else if(!CARDINALITY.contains(cardinality)){
                issues.add(new Issue(path+"/cardinality","LINK_CARDINALITY_INVALID","不支持的关联基数"));invalid=true;
            }
            if(!link.has("evidenceRequired"))
                issues.add(new Issue(path+"/evidenceRequired","LINK_EVIDENCE_REQUIRED","请明确是否需要来源证据"));
        }
        if(!issues.isEmpty()) return new Result(invalid?"INVALID":"INCOMPLETE",issues,null);
        // Build the deterministic complete Native V0.2 candidate; never imply publish/approval.
        ObjectNode candidate=mapper.createObjectNode();
        candidate.put("schemaVersion",NATIVE_VERSION);
        candidate.put("packageId",packageId);
        candidate.put("version","0.1.0");
        candidate.set("imports",mapper.createArrayNode());
        ArrayNode cleanTypes=mapper.createArrayNode();
        for(JsonNode object:objects){
            ObjectNode clean=(ObjectNode)object.deepCopy();
            if(optionalMissing(clean.get("label"))) clean.remove("label");
            if(optionalMissing(clean.get("description"))) clean.remove("description");
            ArrayNode cleanProperties=mapper.createArrayNode();
            for(JsonNode prop:object.get("properties")){
                ObjectNode cp=(ObjectNode)prop.deepCopy();
                if(optionalMissing(cp.get("label"))) cp.remove("label");
                if(cp.has("enumValues") && !"ENUM".equals(get(cp,"valueType"))) cp.remove("enumValues");
                cleanProperties.add(cp);
            }
            clean.set("properties",cleanProperties);
            cleanTypes.add(clean);
        }
        candidate.set("objectTypes",cleanTypes);
        ArrayNode cleanLinks=mapper.createArrayNode();
        for(JsonNode link:links){
            ObjectNode clean=(ObjectNode)link.deepCopy();
            if(optionalMissing(clean.get("label")))clean.remove("label");
            cleanLinks.add(clean);
        }
        candidate.set("linkTypes",cleanLinks);
        for(String key:List.of("bindings","linkBindings","objectSets","functions","rules","decisionStrategies","policies","actions"))
            candidate.set(key,mapper.createArrayNode());
        List<Issue> schemaIssues=NativePackageSchema.validate(candidate);
        if(!schemaIssues.isEmpty())
            return new Result("INVALID",schemaIssues,null);
        return new Result("VALID",List.of(),candidate);
    }
}
