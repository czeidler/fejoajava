/*
 * Copyright 2015.
 * Distributed under the terms of the GPLv3 License.
 *
 * Authors:
 *      Clemens Zeidler <czei002@aucklanduni.ac.nz>
 */
package org.fejoa.server;

import org.apache.http.HttpEntity;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.mime.HttpMultipartMode;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.fejoa.library.remote2.HTMLRequest;
import org.fejoa.library.remote2.JsonRPCHandler;
import org.fejoa.library.support.StreamHelper;

import javax.servlet.MultipartConfigElement;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.Part;
import java.io.*;
import java.util.ArrayList;
import java.util.List;


public class Portal extends AbstractHandler {
    final private List<JsonRequestHandler> jsonHandlers = new ArrayList<>();

    private void addJsonHandler(JsonRequestHandler handler) {
        jsonHandlers.add(handler);
    }

    public Portal() {
        addJsonHandler(new JsonPingHandler());
        addJsonHandler(new JsonCreateAccountHandler());
    }

    public void handle(String s, Request request, HttpServletRequest httpServletRequest,
                       HttpServletResponse response) throws IOException, ServletException {
        response.setContentType("text/plain;charset=utf-8");
        response.setStatus(HttpServletResponse.SC_OK);
        request.setHandled(true);

        final MultipartConfigElement MULTI_PART_CONFIG = new MultipartConfigElement(
                System.getProperty("java.io.tmpdir"));
        if (request.getContentType() != null && request.getContentType().startsWith("multipart/form-data")) {
            request.setAttribute(Request.__MULTIPART_CONFIG_ELEMENT, MULTI_PART_CONFIG);
        }

        Part messagePart = request.getPart(HTMLRequest.MESSAGE_KEY);
        Part data = request.getPart(HTMLRequest.DATA_KEY);

        if (messagePart == null) {
            makeResponse(response, "empty request!", null);
            return;
        }

        StringWriter stringWriter = new StringWriter();
        StreamHelper.copy(messagePart.getInputStream(), stringWriter);
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        String returnHeader = handleJson(stringWriter.toString(), (data != null) ? data.getInputStream() : null,
                outputStream);
        makeResponse(response, returnHeader, new ByteArrayInputStream(outputStream.toByteArray()));
    }

    private void makeResponse(HttpServletResponse response, String header, InputStream data) throws IOException {
        MultipartEntityBuilder builder = MultipartEntityBuilder.create();
        builder.setMode(HttpMultipartMode.BROWSER_COMPATIBLE);
        builder.addTextBody(HTMLRequest.MESSAGE_KEY, header, ContentType.DEFAULT_TEXT);
        if (data != null) {
            builder.addBinaryBody(HTMLRequest.DATA_KEY, data, ContentType.DEFAULT_BINARY,
                    HTMLRequest.DATA_FILE);
        }
        HttpEntity entity = builder.build();
        response.getOutputStream().write(entity.getContentType().toString().getBytes());
        response.getOutputStream().write('\n');
        entity.writeTo(response.getOutputStream());
    }

    private String handleJson(String message, InputStream data, OutputStream outputStream) {
        JsonRPCHandler jsonRPCHandler;
        try {
            jsonRPCHandler = new JsonRPCHandler(message);
        } catch (Exception e) {
            e.printStackTrace();
            return JsonRPCHandler.makeError(-1, JsonRequestHandler.Errors.INVALID_JSON_REQUEST,
                    "can't parse json");
        }

        String method = jsonRPCHandler.getMethod();
        for (JsonRequestHandler handler : jsonHandlers) {
            if (!handler.getMethod().equals(method))
                continue;

            String returnMessage = handler.handle(jsonRPCHandler, jsonRPCHandler.getParams(), data, outputStream);
            if (returnMessage != null)
                return returnMessage;
        }

        return jsonRPCHandler.makeError(JsonRequestHandler.Errors.NO_HANDLER_FOR_REQUEST, "can't handle request");
    }
}