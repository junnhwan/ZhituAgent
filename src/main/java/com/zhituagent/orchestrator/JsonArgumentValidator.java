package com.zhituagent.orchestrator;

import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonNumberSchema;
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Cheap structural validator for {@link JsonObjectSchema} — covers the subset
 * we actually use today (required properties, additionalProperties=false,
 * primitive type checks). Designed to give the LLM a precise observation when
 * its tool arguments fail validation, so it can self-correct on the next turn.
 *
 * <p>Not a full JSON Schema implementation — anyOf / oneOf / pattern / format
 * are out of scope. Pulling in everit-org/json-schema-validator was rejected
 * to keep the dep tree tight.
 */
final class JsonArgumentValidator {

    private JsonArgumentValidator() {
    }

    static ValidationResult validate(JsonObjectSchema schema, Map<String, Object> arguments) {
        if (schema == null) {
            return ValidationResult.ok();
        }
        Map<String, JsonSchemaElement> properties = schema.properties();
        List<String> required = schema.required() == null ? List.of() : schema.required();
        Boolean additionalProperties = schema.additionalProperties();

        List<String> errors = new ArrayList<>();
        Map<String, Object> safeArguments = arguments == null ? Map.of() : arguments;

        for (String requiredKey : required) {
            if (!safeArguments.containsKey(requiredKey) || safeArguments.get(requiredKey) == null) {
                errors.add("missing required property '" + requiredKey + "'");
            }
        }

        for (Map.Entry<String, Object> entry : safeArguments.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            JsonSchemaElement propertySchema = properties == null ? null : properties.get(key);
            if (propertySchema == null) {
                if (Boolean.FALSE.equals(additionalProperties)) {
                    errors.add("unexpected property '" + key + "' (additionalProperties is false)");
                }
                continue;
            }
            String typeError = checkType(key, value, propertySchema);
            if (typeError != null) {
                errors.add(typeError);
            }
        }

        return errors.isEmpty() ? ValidationResult.ok() : new ValidationResult(false, List.copyOf(errors));
    }

    private static String checkType(String key, Object value, JsonSchemaElement propertySchema) {
        if (value == null) {
            return null;
        }
        if (propertySchema instanceof JsonStringSchema && !(value instanceof String)) {
            return "property '" + key + "' must be a string";
        }
        if (propertySchema instanceof JsonIntegerSchema && !(value instanceof Number)) {
            return "property '" + key + "' must be an integer";
        }
        if (propertySchema instanceof JsonNumberSchema && !(value instanceof Number)) {
            return "property '" + key + "' must be a number";
        }
        if (propertySchema instanceof JsonBooleanSchema && !(value instanceof Boolean)) {
            return "property '" + key + "' must be a boolean";
        }
        return null;
    }

    record ValidationResult(boolean valid, List<String> errors) {

        static ValidationResult ok() {
            return new ValidationResult(true, List.of());
        }

        String formatErrors() {
            return String.join("; ", errors);
        }
    }
}
