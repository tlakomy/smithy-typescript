/*
 * Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package software.amazon.smithy.typescript.codegen.integration;

import static software.amazon.smithy.model.knowledge.HttpBinding.Location;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Logger;
import software.amazon.smithy.codegen.core.CodegenException;
import software.amazon.smithy.codegen.core.Symbol;
import software.amazon.smithy.codegen.core.SymbolProvider;
import software.amazon.smithy.codegen.core.SymbolReference;
import software.amazon.smithy.model.knowledge.HttpBinding;
import software.amazon.smithy.model.knowledge.HttpBindingIndex;
import software.amazon.smithy.model.knowledge.TopDownIndex;
import software.amazon.smithy.model.shapes.BlobShape;
import software.amazon.smithy.model.shapes.BooleanShape;
import software.amazon.smithy.model.shapes.CollectionShape;
import software.amazon.smithy.model.shapes.DocumentShape;
import software.amazon.smithy.model.shapes.MemberShape;
import software.amazon.smithy.model.shapes.NumberShape;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.shapes.ShapeIndex;
import software.amazon.smithy.model.shapes.StringShape;
import software.amazon.smithy.model.shapes.TimestampShape;
import software.amazon.smithy.model.traits.HttpTrait;
import software.amazon.smithy.model.traits.TimestampFormatTrait.Format;
import software.amazon.smithy.typescript.codegen.ApplicationProtocol;
import software.amazon.smithy.typescript.codegen.TypeScriptWriter;
import software.amazon.smithy.utils.ListUtils;
import software.amazon.smithy.utils.OptionalUtils;

/**
 * Abstract implementation useful for all protocols that use HTTP bindings.
 */
public abstract class HttpBindingProtocolGenerator implements ProtocolGenerator {

    private static final Logger LOGGER = Logger.getLogger(HttpBindingProtocolGenerator.class.getName());

    private final Set<Shape> documentSerializingShapes = new TreeSet<>();
    private final Set<Shape> documentDeserializingShapes = new TreeSet<>();

    @Override
    public ApplicationProtocol getApplicationProtocol() {
        return ApplicationProtocol.createDefaultHttpApplicationProtocol();
    }

    /**
     * Gets the default serde format for timestamps.
     *
     * @return Returns the default format.
     */
    protected abstract Format getDocumentTimestampFormat();

    /**
     * Gets the default content-type when a document is synthesized in the body.
     *
     * @return Returns the default content-type.
     */
    protected abstract String getDocumentContentType();

    /**
     * Generates serialization functions for shapes in the passed set. These functions
     * should return a value that can then be serialized by the implementation of
     * {@code serializeDocument}. The {@link DocumentShapeSerVisitor} and {@link DocumentMemberSerVisitor}
     * are provided to reduce the effort of this implementation.
     *
     * @param context The generation context.
     * @param shapes The shapes to generate serialization for.
     */
    protected abstract void generateDocumentShapeSerializers(GenerationContext context, Set<Shape> shapes);

    /**
     * Generates deserialization functions for shapes in the passed set. These functions
     * should return a value that can then be deserialized by the implementation of
     * {@code deserializeDocument}. The {@link DocumentShapeDeserVisitor} and
     * {@link DocumentMemberDeserVisitor} are provided to reduce the effort of this implementation.
     *
     * @param context The generation context.
     * @param shapes The shapes to generate deserialization for.
     */
    protected abstract void generateDocumentShapeDeserializers(GenerationContext context, Set<Shape> shapes);

    @Override
    public void generateSharedComponents(GenerationContext context) {
        generateDocumentShapeSerializers(context, documentSerializingShapes);
        generateDocumentShapeDeserializers(context, documentDeserializingShapes);

        TypeScriptWriter writer = context.getWriter();

        SymbolReference responseType = getApplicationProtocol().getResponseType();
        writer.addImport("ResponseMetadata", "__ResponseMetadata", "@aws-sdk/types");
        writer.openBlock("const deserializeMetadata = (output: $T): __ResponseMetadata => ({", "});", responseType,
                () -> {
                    writer.write("httpStatusCode: output.statusCode,");
                    writer.write("httpHeaders: output.headers,");
                    writer.write("requestId: output.headers[\"x-amzn-requestid\"]");
                });
        writer.write("");
    }

    /**
     * Detects if the target shape is expressed as a native simple type.
     *
     * @param target The shape of the value being provided.
     * @return Returns if the shape is a native simple type.
     */
    private boolean isNativeSimpleType(Shape target) {
        return target instanceof BooleanShape || target instanceof DocumentShape
                       || target instanceof NumberShape || target instanceof StringShape;
    }

    @Override
    public void generateRequestSerializers(GenerationContext context) {
        TopDownIndex topDownIndex = context.getModel().getKnowledge(TopDownIndex.class);

        Set<OperationShape> containedOperations = new TreeSet<>(
                topDownIndex.getContainedOperations(context.getService()));
        for (OperationShape operation : containedOperations) {
            OptionalUtils.ifPresentOrElse(
                    operation.getTrait(HttpTrait.class),
                    httpTrait -> generateOperationSerializer(context, operation, httpTrait),
                    () -> LOGGER.warning(String.format(
                            "Unable to generate %s protocol request bindings for %s because it does not have an "
                            + "http binding trait", getName(), operation.getId())));
        }
    }

    @Override
    public void generateResponseDeserializers(GenerationContext context) {
        TopDownIndex topDownIndex = context.getModel().getKnowledge(TopDownIndex.class);

        Set<OperationShape> containedOperations = new TreeSet<>(
                topDownIndex.getContainedOperations(context.getService()));
        for (OperationShape operation : containedOperations) {
            OptionalUtils.ifPresentOrElse(
                    operation.getTrait(HttpTrait.class),
                    httpTrait -> generateOperationDeserializer(context, operation, httpTrait),
                    () -> LOGGER.warning(String.format(
                            "Unable to generate %s protocol response bindings for %s because it does not have an "
                            + "http binding trait", getName(), operation.getId())));
        }
    }

    private void generateOperationSerializer(
            GenerationContext context,
            OperationShape operation,
            HttpTrait trait
    ) {
        SymbolProvider symbolProvider = context.getSymbolProvider();
        Symbol symbol = symbolProvider.toSymbol(operation);
        SymbolReference requestType = getApplicationProtocol().getRequestType();
        HttpBindingIndex bindingIndex = context.getModel().getKnowledge(HttpBindingIndex.class);
        TypeScriptWriter writer = context.getWriter();

        // Ensure that the request type is imported.
        writer.addUseImports(requestType);
        writer.addImport("SerdeContext", "SerdeContext", "@aws-sdk/types");
        writer.addImport("Endpoint", "__Endpoint", "@aws-sdk/types");
        // e.g., serializeAws_restJson1_1ExecuteStatement
        String methodName = ProtocolGenerator.getSerFunctionName(symbol, getName());
        // Add the normalized input type.
        String inputType = symbol.getName() + "Input";
        writer.addImport(inputType, inputType, symbol.getNamespace());

        writer.openBlock("export function $L(\n"
                       + "  input: $L,\n"
                       + "  context: SerdeContext\n"
                       + "): $T {", "}", methodName, inputType, requestType, () -> {
            List<HttpBinding> labelBindings = writeRequestLabels(context, operation, bindingIndex, trait);
            List<HttpBinding> queryBindings = writeRequestQueryString(context, operation, bindingIndex);
            writeHeaders(context, operation, bindingIndex);
            List<HttpBinding> documentBindings = writeRequestBody(context, operation, bindingIndex);

            writer.openBlock("return new $T({", "});", requestType, () -> {
                writer.write("...context.endpoint,");
                writer.write("protocol: \"https\",");
                writer.write("method: $S,", trait.getMethod());
                if (labelBindings.isEmpty()) {
                    writer.write("path: $S,", trait.getUri());
                } else {
                    writer.write("path: resolvedPath,");
                }
                writer.write("headers: headers,");
                if (!documentBindings.isEmpty()) {
                    // Track all shapes bound to the document so their serializers may be generated.
                    documentBindings.stream()
                            .map(HttpBinding::getMember)
                            .map(member -> context.getModel().getShapeIndex().getShape(member.getTarget()).get())
                            .forEach(documentSerializingShapes::add);
                    writer.write("body: body,");
                }
                if (!queryBindings.isEmpty()) {
                    writer.write("query: query,");
                }
            });
        });

        writer.write("");
    }

    private List<HttpBinding> writeRequestLabels(
            GenerationContext context,
            OperationShape operation,
            HttpBindingIndex bindingIndex,
            HttpTrait trait
    ) {
        TypeScriptWriter writer = context.getWriter();
        SymbolProvider symbolProvider = context.getSymbolProvider();
        List<HttpBinding> labelBindings = bindingIndex.getRequestBindings(operation, Location.LABEL);

        if (!labelBindings.isEmpty()) {
            ShapeIndex index = context.getModel().getShapeIndex();
            writer.write("let resolvedPath = $S;", trait.getUri());
            for (HttpBinding binding : labelBindings) {
                String memberName = symbolProvider.toMemberName(binding.getMember());
                writer.openBlock("if (input.$L !== undefined) {", "}", memberName, () -> {
                    Shape target = index.getShape(binding.getMember().getTarget()).get();
                    String labelValue = getInputValue(context, binding.getLocation(), "input." + memberName,
                            binding.getMember(), target);
                    writer.write("resolvedPath = resolvedPath.replace('{$S}', $L);", memberName, labelValue);
                });
            }
        }

        return labelBindings;
    }

    private List<HttpBinding> writeRequestQueryString(
            GenerationContext context,
            OperationShape operation,
            HttpBindingIndex bindingIndex
    ) {
        TypeScriptWriter writer = context.getWriter();
        SymbolProvider symbolProvider = context.getSymbolProvider();
        List<HttpBinding> queryBindings = bindingIndex.getRequestBindings(operation, Location.QUERY);

        if (!queryBindings.isEmpty()) {
            ShapeIndex index = context.getModel().getShapeIndex();
            writer.write("let query: any = {};");
            for (HttpBinding binding : queryBindings) {
                String memberName = symbolProvider.toMemberName(binding.getMember());
                writer.openBlock("if (input.$L !== undefined) {", "}", memberName, () -> {
                    Shape target = index.getShape(binding.getMember().getTarget()).get();
                    String queryValue = getInputValue(context, binding.getLocation(), "input." + memberName,
                            binding.getMember(), target);
                    writer.write("query['$L'] = $L;", binding.getLocationName(), queryValue);
                });
            }
        }

        return queryBindings;
    }

    private void writeHeaders(
            GenerationContext context,
            OperationShape operation,
            HttpBindingIndex bindingIndex
    ) {
        TypeScriptWriter writer = context.getWriter();
        SymbolProvider symbolProvider = context.getSymbolProvider();

        // Headers are always present either from the default document or the payload.
        writer.write("let headers: any = {};");
        writer.write("headers['Content-Type'] = $S;", bindingIndex.determineRequestContentType(
                operation, getDocumentContentType()));

        operation.getInput().ifPresent(outputId -> {
            ShapeIndex index = context.getModel().getShapeIndex();
            for (HttpBinding binding : bindingIndex.getRequestBindings(operation, Location.HEADER)) {
                String memberName = symbolProvider.toMemberName(binding.getMember());
                writer.openBlock("if (input.$L !== undefined) {", "}", memberName, () -> {
                    Shape target = index.getShape(binding.getMember().getTarget()).get();
                    String headerValue = getInputValue(context, binding.getLocation(), "input." + memberName,
                            binding.getMember(), target);
                    writer.write("headers['$L'] = $L;", binding.getLocationName(), headerValue);
                });
            }

            // Handle assembling prefix headers.
            for (HttpBinding binding : bindingIndex.getRequestBindings(operation, Location.PREFIX_HEADERS)) {
                String memberName = symbolProvider.toMemberName(binding.getMember());
                writer.openBlock("if (input.$L !== undefined) {", "}", memberName, () -> {
                    Shape target = index.getShape(binding.getMember().getTarget()).get();
                    // Iterate through each entry in the member.
                    writer.openBlock("Object.keys(input.$L).forEach(suffix -> {", "});", memberName, () -> {
                        String headerValue = getInputValue(context, binding.getLocation(),
                                "input." + memberName + "[suffix]", binding.getMember(), target);
                        // Append the suffix to the defined prefix and serialize the value in to that key.
                        writer.write("headers['$L' + suffix] = $L;", binding.getLocationName(), headerValue);
                    });
                });
            }
        });
    }

    private List<HttpBinding> writeRequestBody(
            GenerationContext context,
            OperationShape operation,
            HttpBindingIndex bindingIndex
    ) {
        TypeScriptWriter writer = context.getWriter();
        List<HttpBinding> documentBindings = bindingIndex.getRequestBindings(operation, Location.DOCUMENT);
        documentBindings.sort(Comparator.comparing(HttpBinding::getMemberName));
        List<HttpBinding> payloadBindings = bindingIndex.getRequestBindings(operation, Location.PAYLOAD);

        if (!documentBindings.isEmpty()) {
            // Write the default `body` property.
            context.getWriter().write("let body: any = undefined;");
            serializeInputDocument(context, operation, documentBindings);
            return documentBindings;
        }
        if (!payloadBindings.isEmpty()) {
            // TODO Validate payload structures are handled correctly.
            SymbolProvider symbolProvider = context.getSymbolProvider();
            // There can only be one payload binding.
            HttpBinding binding = payloadBindings.get(0);
            String memberName = symbolProvider.toMemberName(binding.getMember());
            Shape target = context.getModel().getShapeIndex().getShape(binding.getMember().getTarget()).get();
            writer.write("let body: any = $L;", getInputValue(
                    context, Location.PAYLOAD, "input." + memberName, binding.getMember(), target));
            return payloadBindings;
        }

        return ListUtils.of();
    }

    /**
     * Given context and a source of data, generate an input value provider for the
     * shape. This may use native types (like getting Date formats for timestamps,)
     * converters (like a base64Encoder,) or invoke complex type serializers to
     * manipulate the dataSource into the proper input content.
     *
     * @param context The generation context.
     * @param bindingType How this value is bound to the operation input.
     * @param dataSource The in-code location of the data to provide an input of
     *                   ({@code input.foo}, {@code entry}, etc.)
     * @param member The member that points to the value being provided.
     * @param target The shape of the value being provided.
     * @return Returns a value or expression of the input value.
     */
    private String getInputValue(
            GenerationContext context,
            Location bindingType,
            String dataSource,
            MemberShape member,
            Shape target
    ) {
        if (isNativeSimpleType(target)) {
            return dataSource;
        } else if (target instanceof TimestampShape) {
            HttpBindingIndex httpIndex = context.getModel().getKnowledge(HttpBindingIndex.class);
            Format format = httpIndex.determineTimestampFormat(member, bindingType, getDocumentTimestampFormat());
            return getTimestampInputParam(dataSource, member, format);
        } else if (target instanceof BlobShape) {
            return getBlobInputParam(bindingType, dataSource);
        } else if (target instanceof CollectionShape) {
            return getCollectionInputParam(bindingType, dataSource);
        }

        throw new CodegenException(String.format(
                "Unsupported %s binding of %s to %s in %s using the %s protocol",
                bindingType, member.getMemberName(), target.getType(), member.getContainer(), getName()));
    }

    /**
     * Given a format and a source of data, generate an input value provider for the
     * timestamp.
     *
     * @param dataSource The in-code location of the data to provide an input of
     *                   ({@code input.foo}, {@code entry}, etc.)
     * @param member The member that points to the value being provided.
     * @param format The timestamp format to provide.
     * @return Returns a value or expression of the input timestamp.
     */
    private String getTimestampInputParam(String dataSource, MemberShape member, Format format) {
        switch (format) {
            case DATE_TIME:
                return dataSource + ".toISOString()";
            case EPOCH_SECONDS:
                return "Math.round(" + dataSource + ".getTime() / 1000)";
            case HTTP_DATE:
                return dataSource + ".toUTCString()";
            default:
                throw new CodegenException("Unexpected timestamp format `" + format.toString() + "` on " + member);
        }
    }

    /**
     * Given context and a source of data, generate an input value provider for the
     * blob. By default, this base64 encodes content in headers and query strings,
     * and passes through for payloads.
     *
     * @param dataSource The in-code location of the data to provide an output of
     *                   ({@code output.foo}, {@code entry}, etc.)
     * @param bindingType How this value is bound to the operation input.
     * @return Returns a value or expression of the input blob.
     */
    private String getBlobInputParam(Location bindingType, String dataSource) {
        switch (bindingType) {
            case PAYLOAD:
                return dataSource;
            case HEADER:
            case QUERY:
                // Encode these to base64.
                return "context.base64Encoder(" + dataSource + ")";
            default:
                throw new CodegenException("Unexpected blob binding location `" + bindingType + "`");
        }
    }

    /**
     * Given context and a source of data, generate an input value provider for the
     * collection. By default, this separates the list with commas in headers, and
     * relies on the HTTP implementation for query strings.
     *
     * @param bindingType How this value is bound to the operation input.
     * @param dataSource The in-code location of the data to provide an output of
     *                   ({@code output.foo}, {@code entry}, etc.)
     * @return Returns a value or expression of the input collection.
     */
    private String getCollectionInputParam(
            Location bindingType,
            String dataSource
    ) {
        switch (bindingType) {
            case HEADER:
                // Join these values with commas.
                return "(" + dataSource + " || []).toString()";
            case QUERY:
                return dataSource;
            default:
                throw new CodegenException("Unexpected collection binding location `" + bindingType + "`");
        }
    }

    /**
     * Writes the code needed to serialize the input document of a request.
     *
     * Implementations of this method are expected to set a value to the
     * {@code body} variable that will be serialized as the request body.
     * This variable will already be defined in scope.
     *
     * <p>For example:
     *
     * <pre>{@code
     * let bodyParams: any = {};
     * if (input.barValue !== undefined) {
     *   bodyParams['barValue'] = input.barValue;
     * }
     * body = JSON.stringify(bodyParams);
     * }</pre>
     *
     * @param context The generation context.
     * @param operation The operation being generated.
     * @param documentBindings The bindings to place in the document.
     */
    protected abstract void serializeInputDocument(
            GenerationContext context,
            OperationShape operation,
            List<HttpBinding> documentBindings
    );

    private void generateOperationDeserializer(
            GenerationContext context,
            OperationShape operation,
            HttpTrait trait
    ) {
        SymbolProvider symbolProvider = context.getSymbolProvider();
        Symbol symbol = symbolProvider.toSymbol(operation);
        SymbolReference responseType = getApplicationProtocol().getResponseType();
        HttpBindingIndex bindingIndex = context.getModel().getKnowledge(HttpBindingIndex.class);
        ShapeIndex shapeIndex = context.getModel().getShapeIndex();
        TypeScriptWriter writer = context.getWriter();

        // Ensure that the response type is imported.
        writer.addUseImports(responseType);
        writer.addImport("SerdeContext", "SerdeContext", "@aws-sdk/types");
        // e.g., deserializeAws_restJson1_1ExecuteStatement
        String methodName = ProtocolGenerator.getDeserFunctionName(symbol, getName());
        String errorMethodName = methodName + "Error";
        // Add the normalized output type.
        String outputType = symbol.getName() + "Output";
        writer.addImport(outputType, outputType, symbol.getNamespace());

        // Handle the general response.
        writer.openBlock("export async function $L(\n"
                       + "  output: $T,\n"
                       + "  context: SerdeContext\n"
                       + "): Promise<$L> {", "}", methodName, responseType, outputType, () -> {
            // Redirect error deserialization to the dispatcher
            writer.openBlock("if (output.statusCode !== $L) {", "}", trait.getCode(), () -> {
                writer.write("return $L(output, context);", errorMethodName);
            });

            // Start deserializing the response.
            writer.write("let data: any = await parseBody(output.body, context)");
            writer.openBlock("let contents: $L = {", "};", outputType, () -> {
                writer.write("$$metadata: deserializeMetadata(output),");

                // Only set a type and the members if we have output.
                operation.getOutput().ifPresent(outputId -> {
                    writer.write("__type: $S,", outputId.getName());
                    List<HttpBinding> documentBindings = bindingIndex.getResponseBindings(operation, Location.DOCUMENT);
                    // Track all shapes bound to the document so their deserializers may be generated.
                    documentBindings.forEach(binding -> {
                        // Set all the members to undefined to meet type constraints.
                        writer.write("$L: undefined,", binding.getLocationName());
                        Shape target = shapeIndex.getShape(binding.getMember().getTarget()).get();
                        documentDeserializingShapes.add(target);
                    });
                });
            });
            readHeaders(context, operation, bindingIndex);
            readResponseBody(context, operation, bindingIndex);
            writer.write("return Promise.resolve(contents);");
        });
        writer.write("");

        // Write out the error deserialization dispatcher.
        writer.openBlock("async function $L(\n"
                       + "  output: $T,\n"
                       + "  context: SerdeContext,\n"
                       + "): Promise<$L> {", "}", errorMethodName, responseType, outputType, () -> {
            writer.write("let data: any = await parseBody(output.body, context);");
            writer.write("let response: any;");
            writer.write("let errorCode: String;");
            writeErrorCodeParser(context);
            writer.openBlock("switch (errorCode) {", "}", () -> {
                // Generate the case statement for each error, invoking the specific deserializer.
                operation.getErrors().forEach(errorId -> {
                    Shape error = context.getModel().getShapeIndex().getShape(errorId).get();
                    // Track errors bound to the operation so their deserializers may be generated.
                    documentDeserializingShapes.add(error);
                    Symbol errorSymbol = symbolProvider.toSymbol(error);
                    String errorSerdeMethodName = ProtocolGenerator.getDeserFunctionName(errorSymbol, getName());
                    writer.openBlock("case $S:\ncase $S:", "  break;", errorId.getName(), errorId.toString(), () -> {
                        // Dispatch to the error deserialization function.
                        writer.write("response = $L(data, context);", errorSerdeMethodName);
                    });
                });

                // Build a generic error the best we can for ones we don't know about.
                writer.write("default:").indent()
                        .write("errorCode = errorCode || \"UnknownError\";")
                        .openBlock("response = {", "};", () -> {
                            writer.write("__type: `$L#$${errorCode}`,", operation.getId().getNamespace());
                            writer.write("$$name: errorCode,");
                            writer.write("$$fault: \"client\",");
                        }).dedent();
            });
            writer.write("return Promise.reject(response);");
        });
        writer.write("");
    }

    private void readHeaders(
            GenerationContext context,
            OperationShape operation,
            HttpBindingIndex bindingIndex
    ) {
        TypeScriptWriter writer = context.getWriter();
        SymbolProvider symbolProvider = context.getSymbolProvider();

        ShapeIndex index = context.getModel().getShapeIndex();
        for (HttpBinding binding : bindingIndex.getRequestBindings(operation, Location.HEADER)) {
            String memberName = symbolProvider.toMemberName(binding.getMember());
            writer.openBlock("if (output.headers[$L] !== undefined) {", "}", binding.getLocationName(), () -> {
                Shape target = index.getShape(binding.getMember().getTarget()).get();
                String headerValue = getOutputValue(context, binding.getLocation(),
                        "output.headers[" + binding.getLocationName() + "]", binding.getMember(), target);
                writer.write("contents.$L = $L;", memberName, headerValue);
            });
        }

        // Handle loading up prefix headers.
        List<HttpBinding> prefixHeaderBindings = bindingIndex.getResponseBindings(operation, Location.PREFIX_HEADERS);
        if (!prefixHeaderBindings.isEmpty()) {
            // Run through the headers one time, matching any prefix groups.
            writer.openBlock("Object.keys(output.headers).forEach(header -> {", "});", () -> {
                for (HttpBinding binding : prefixHeaderBindings) {
                    // Generate a single block for each group of prefix headers.
                    writer.openBlock("if (header.startsWith($L)) {", binding.getLocationName(), "}", () -> {
                        String memberName = symbolProvider.toMemberName(binding.getMember());
                        Shape target = context.getModel().getShapeIndex()
                                .getShape(binding.getMember().getTarget()).get();
                        String headerValue = getOutputValue(context, binding.getLocation(),
                                "output.headers[header]", binding.getMember(), target);

                        // Prepare a grab bag for these headers if necessary
                        writer.openBlock("if (contents.$L === undefined) {", "}", memberName, () -> {
                            writer.write("contents.$L: any = {};", memberName);
                        });

                        // Extract the non-prefix portion as the key.
                        writer.write("contents.$L[header.substring(header.length)] = $L;", memberName, headerValue);
                    });
                }
            });
        }
    }

    private List<HttpBinding> readResponseBody(
            GenerationContext context,
            OperationShape operation,
            HttpBindingIndex bindingIndex
    ) {
        TypeScriptWriter writer = context.getWriter();
        List<HttpBinding> documentBindings = bindingIndex.getResponseBindings(operation, Location.DOCUMENT);
        documentBindings.sort(Comparator.comparing(HttpBinding::getMemberName));
        List<HttpBinding> payloadBindings = bindingIndex.getResponseBindings(operation, Location.PAYLOAD);

        if (!documentBindings.isEmpty()) {
            deserializeOutputDocument(context, operation, documentBindings);
            return documentBindings;
        }
        if (!payloadBindings.isEmpty()) {
            // TODO Validate payload structures are handled correctly.
            // There can only be one payload binding.
            HttpBinding binding = payloadBindings.get(0);
            Shape target = context.getModel().getShapeIndex().getShape(binding.getMember().getTarget()).get();
            writer.write("output.$L = $L;", binding.getMemberName(), getOutputValue(context,
                    Location.PAYLOAD, "data", binding.getMember(), target));
            return payloadBindings;
        }
        return ListUtils.of();
    }

    /**
     * Given context and a source of data, generate an output value provider for the
     * shape. This may use native types (like generating a Date for timestamps,)
     * converters (like a base64Decoder,) or invoke complex type deserializers to
     * manipulate the dataSource into the proper output content.
     *
     * @param context The generation context.
     * @param bindingType How this value is bound to the operation output.
     * @param dataSource The in-code location of the data to provide an output of
     *                   ({@code output.foo}, {@code entry}, etc.)
     * @param member The member that points to the value being provided.
     * @param target The shape of the value being provided.
     * @return Returns a value or expression of the output value.
     */
    private String getOutputValue(
            GenerationContext context,
            Location bindingType,
            String dataSource,
            MemberShape member,
            Shape target
    ) {
        if (isNativeSimpleType(target)) {
            return dataSource;
        } else if (target instanceof TimestampShape) {
            HttpBindingIndex httpIndex = context.getModel().getKnowledge(HttpBindingIndex.class);
            Format format = httpIndex.determineTimestampFormat(member, bindingType, getDocumentTimestampFormat());
            return getTimestampOutputParam(dataSource, member, format);
        } else if (target instanceof BlobShape) {
            return getBlobOutputParam(bindingType, dataSource);
        } else if (target instanceof CollectionShape) {
            return getCollectionOutputParam(bindingType, dataSource);
        }

        throw new CodegenException(String.format(
                "Unsupported %s binding of %s to %s in %s using the %s protocol",
                bindingType, member.getMemberName(), target.getType(), member.getContainer(), getName()));
    }

    /**
     * Given a format and a source of data, generate an output value provider for the
     * timestamp.
     *
     * @param dataSource The in-code location of the data to provide an output of
     *                   ({@code output.foo}, {@code entry}, etc.)
     * @param member The member that points to the value being provided.
     * @param format The timestamp format to provide.
     * @return Returns a value or expression of the output timestamp.
     */
    private String getTimestampOutputParam(String dataSource, MemberShape member, Format format) {
        String modifiedSource;
        switch (format) {
            case DATE_TIME:
            case HTTP_DATE:
                modifiedSource = dataSource;
                break;
            case EPOCH_SECONDS:
                // Account for seconds being sent over the wire in some cases where milliseconds are required.
                modifiedSource = dataSource + " % 1 != 0 ? Math.round(" + dataSource + " * 1000) : " + dataSource;
                break;
            default:
                throw new CodegenException("Unexpected timestamp format `" + format.toString() + "` on " + member);
        }

        return "new Date(" + modifiedSource + ")";
    }

    /**
     * Given context and a source of data, generate an output value provider for the
     * blob. By default, this base64 decodes content in headers and passes through
     * for payloads.
     *
     * @param bindingType How this value is bound to the operation output.
     * @param dataSource The in-code location of the data to provide an output of
     *                   ({@code output.foo}, {@code entry}, etc.)
     * @return Returns a value or expression of the output blob.
     */
    private String getBlobOutputParam(Location bindingType, String dataSource) {
        switch (bindingType) {
            case PAYLOAD:
                return dataSource;
            case HEADER:
                // Decode these from base64.
                return "context.base64Decoder(" + dataSource + ")";
            default:
                throw new CodegenException("Unexpected blob binding location `" + bindingType + "`");
        }
    }

    /**
     * Given context and a source of data, generate an output value provider for the
     * collection. By default, this splits a comma separated string in headers.
     *
     * @param bindingType How this value is bound to the operation output.
     * @param dataSource The in-code location of the data to provide an output of
     *                   ({@code output.foo}, {@code entry}, etc.)
     * @return Returns a value or expression of the output collection.
     */
    private String getCollectionOutputParam(
            Location bindingType,
            String dataSource
    ) {
        switch (bindingType) {
            case HEADER:
                // Split these values on commas.
                return "(" + dataSource + " || \"\").split(',')";
            default:
                throw new CodegenException("Unexpected collection binding location `" + bindingType + "`");
        }
    }

    /**
     * Writes the code that loads an {@code errorCode} String with the content used
     * to dispatch errors to specific serializers.
     *
     * <p>Three variables will be in scope:
     *   <ul>
     *       <li>{@code output}: a value of the HttpResponse type.</li>
     *       <li>{@code data}: the contents of the response body.</li>
     *       <li>{@code context}: the SerdeContext.</li>
     *   </ul>
     *
     * <p>For example:
     *
     * <pre>{@code
     * errorCode = output.headers["x-amzn-errortype"].split(':')[0];
     * }</pre>
     *
     * @param context The generation context.
     */
    protected abstract void writeErrorCodeParser(GenerationContext context);

    /**
     * Writes the code needed to deserialize the output document of a response.
     *
     * <p>Implementations of this method are expected to set members in the
     * {@code contents} variable that represents the type generated for the
     * response. This variable will already be defined in scope.
     *
     * The contents of the response body will be available in a {@code data} variable.
     *
     * <p>For example:
     *
     * <pre>{@code
     * if (data.fieldList !== undefined) {
     *   contents.fieldList = deserializeAws_restJson1_1FieldList(data.fieldList, context);
     * }
     * }</pre>
     *
     * @param context The generation context.
     * @param operation The operation being generated.
     * @param documentBindings The bindings to read from the document.
     */
    protected abstract void deserializeOutputDocument(
            GenerationContext context,
            OperationShape operation,
            List<HttpBinding> documentBindings
    );
}