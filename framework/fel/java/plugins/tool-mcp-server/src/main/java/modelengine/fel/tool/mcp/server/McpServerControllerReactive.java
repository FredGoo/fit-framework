/*---------------------------------------------------------------------------------------------
 *  Copyright (c) 2024 Huawei Technologies Co., Ltd. All rights reserved.
 *  This file is a part of the ModelEngine Project.
 *  Licensed under the MIT License. See License.txt in the project root for license information.
 *--------------------------------------------------------------------------------------------*/

package modelengine.fel.tool.mcp.server;

import static modelengine.fitframework.inspection.Validation.notNull;
import static modelengine.fitframework.util.ObjectUtils.cast;

import modelengine.fel.tool.mcp.entity.Event;
import modelengine.fel.tool.mcp.entity.JsonRpc;
import modelengine.fel.tool.mcp.entity.Method;
import modelengine.fel.tool.mcp.server.handler.InitializeHandler;
import modelengine.fel.tool.mcp.server.handler.PingHandler;
import modelengine.fel.tool.mcp.server.handler.ToolCallHandler;
import modelengine.fel.tool.mcp.server.handler.ToolListHandler;
import modelengine.fel.tool.mcp.server.handler.UnsupportedMethodHandler;
import modelengine.fit.http.annotation.GetMapping;
import modelengine.fit.http.annotation.PostMapping;
import modelengine.fit.http.annotation.RequestBody;
import modelengine.fit.http.annotation.RequestQuery;
import modelengine.fit.http.entity.TextEvent;
import modelengine.fit.http.server.HttpClassicServerResponse;
import modelengine.fitframework.annotation.Component;
import modelengine.fitframework.annotation.Fit;
import modelengine.fitframework.flowable.Choir;
import modelengine.fitframework.flowable.Emitter;
import modelengine.fitframework.flowable.Solo;
import modelengine.fitframework.log.Logger;
import modelengine.fitframework.schedule.ExecutePolicy;
import modelengine.fitframework.schedule.Task;
import modelengine.fitframework.schedule.ThreadPoolExecutors;
import modelengine.fitframework.schedule.ThreadPoolScheduler;
import modelengine.fitframework.serialization.ObjectSerializer;
import modelengine.fitframework.util.StringUtils;
import modelengine.fitframework.util.UuidUtils;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于 Choir 响应式流的 MCP 服务器控制器改造版本。
 * 
 * <p>此版本将消息处理逻辑改造为响应式流水线，提供以下优势：</p>
 * <ul>
 *   <li>非阻塞异步处理</li>
 *   <li>流式错误处理</li>
 *   <li>可组合的处理流水线</li>
 *   <li>更好的线程池利用</li>
 * </ul>
 *
 * @author Fred
 * @since 2025-08-15
 */
@Component
public class McpServerControllerReactive implements McpServer.ToolsChangedObserver {
    private static final Logger log = Logger.get(McpServerControllerReactive.class);
    private static final String MESSAGE_PATH = "/mcp/message";
    private static final String RESPONSE_OK = StringUtils.EMPTY;

    private final Map<String, Emitter<TextEvent>> emitters = new ConcurrentHashMap<>();
    private final Map<String, HttpClassicServerResponse> responses = new ConcurrentHashMap<>();
    private final Map<String, MessageHandler> methodHandlers = new HashMap<>();
    private final MessageHandler unsupportedMethodHandler = new UnsupportedMethodHandler();
    private final ObjectSerializer serializer;

    /**
     * 构造函数，初始化消息处理器和定时任务。
     *
     * @param serializer JSON 序列化器
     * @param mcpServer MCP 服务器实例
     */
    public McpServerControllerReactive(@Fit(alias = "json") ObjectSerializer serializer, McpServer mcpServer) {
        this.serializer = notNull(serializer, "The json serializer cannot be null.");
        notNull(mcpServer, "The MCP server cannot be null.");
        mcpServer.registerToolsChangedObserver(this);

        // 初始化消息处理器
        this.methodHandlers.put(Method.INITIALIZE.code(), new InitializeHandler(mcpServer));
        this.methodHandlers.put(Method.PING.code(), new PingHandler());
        this.methodHandlers.put(Method.TOOLS_LIST.code(), new ToolListHandler(mcpServer));
        this.methodHandlers.put(Method.TOOLS_CALL.code(), new ToolCallHandler(mcpServer, this.serializer));

        // 启动通道检测定时任务
        startChannelDetector();
    }

    /**
     * 启动通道检测定时任务。
     */
    private void startChannelDetector() {
        ThreadPoolScheduler channelDetectorScheduler = ThreadPoolScheduler.custom()
                .corePoolSize(1)
                .isDaemonThread(true)
                .threadPoolName("mcp-server-channel-detector")
                .build();
        
        channelDetectorScheduler.schedule(Task.builder()
                .policy(ExecutePolicy.fixedDelay(10000))
                .runnable(() -> {
                    // 清理断开的连接
                    responses.entrySet().removeIf(entry -> {
                        if (entry.getValue().isClosed()) {
                            String sessionId = entry.getKey();
                            emitters.remove(sessionId);
                            log.info("Removed closed SSE channel. [sessionId={}]", sessionId);
                            return true;
                        }
                        return false;
                    });
                })
                .build());
    }

    /**
     * 创建 SSE 连接，返回响应式流。
     *
     * @param response HTTP 响应对象
     * @return 包含 TextEvent 的响应式流
     */
    @GetMapping(path = "/sse")
    public Choir<TextEvent> createSse(HttpClassicServerResponse response) {
        String sessionId = UuidUtils.randomUuidString();
        this.responses.put(sessionId, response);
        log.info("New SSE channel for MCP server created. [sessionId={}]", sessionId);
        
        return Choir.create(emitter -> {
            emitters.put(sessionId, emitter);
            String data = MESSAGE_PATH + "?session_id=" + sessionId;
            TextEvent textEvent = TextEvent.custom()
                    .id(sessionId)
                    .event(Event.ENDPOINT.code())
                    .data(data)
                    .build();
            emitter.emit(textEvent);
            log.info("Send MCP endpoint. [endpoint={}]", data);
        });
    }

    /**
     * 接收并处理 MCP 消息的响应式版本。
     * 
     * <p>使用 Choir 响应式流来处理消息，提供非阻塞的异步处理能力。</p>
     *
     * @param sessionId 会话 ID
     * @param request JSON-RPC 请求
     * @return 处理结果
     */
    @PostMapping(path = MESSAGE_PATH)
    public Object receiveMcpMessageReactive(@RequestQuery(name = "session_id") String sessionId,
            @RequestBody Map<String, Object> request) {
        log.info("Receive MCP message. [sessionId={}, message={}]", sessionId, request);
        
        // 创建消息处理流水线
        createMessageProcessingPipeline(sessionId, request)
                .subscribeOn(ThreadPoolExecutors.io()) // 在 IO 线程池中执行
                .subscribe(
                    // 订阅时的行为
                    subscription -> log.debug("Message processing started. [sessionId={}]", sessionId),
                    // 处理每个事件
                    (subscription, textEvent) -> {
                        Emitter<TextEvent> emitter = emitters.get(sessionId);
                        if (emitter != null) {
                            emitter.emit(textEvent);
                            log.info("Send MCP message. [message={}]", textEvent.getData());
                        }
                    },
                    // 完成时的行为
                    subscription -> log.debug("Message processing completed. [sessionId={}]", sessionId),
                    // 错误处理
                    (subscription, exception) -> {
                        log.error("Failed to process MCP message. [sessionId={}]", sessionId, exception);
                        handleErrorResponse(sessionId, request, exception);
                    }
                );
        
        return RESPONSE_OK;
    }

    /**
     * 创建消息处理流水线。
     * 
     * <p>将消息处理逻辑组织为响应式流水线，包括：</p>
     * <ul>
     *   <li>消息验证</li>
     *   <li>处理器路由</li>
     *   <li>异步执行</li>
     *   <li>响应构建</li>
     *   <li>序列化</li>
     *   <li>事件构建</li>
     * </ul>
     *
     * @param sessionId 会话 ID
     * @param request 请求数据
     * @return 包含处理结果的响应式流
     */
    private Choir<TextEvent> createMessageProcessingPipeline(String sessionId, Map<String, Object> request) {
        return Choir.just(request)
                // 1. 验证请求
                .filter(req -> {
                    Object id = req.get("id");
                    if (id == null) {
                        log.debug("Request without ID indicates notification, ignoring. [sessionId={}]", sessionId);
                        return false;
                    }
                    return true;
                })
                // 2. 提取方法名并路由到处理器
                .map(req -> {
                    String method = cast(req.getOrDefault("method", StringUtils.EMPTY));
                    MessageHandler handler = methodHandlers.getOrDefault(method, unsupportedMethodHandler);
                    return new MessageProcessingContext(req, handler);
                })
                // 3. 异步执行处理器
                .flatMap(context -> processMessageAsync(context))
                // 4. 构建响应
                .map(context -> {
                    Object id = context.getRequest().get("id");
                    JsonRpc.Response<Object> response;
                    
                    if (context.getException() != null) {
                        response = JsonRpc.createResponseWithError(id, context.getException().getMessage());
                    } else {
                        response = JsonRpc.createResponse(id, context.getResult());
                    }
                    
                    return response;
                })
                // 5. 序列化响应
                .map(response -> serializer.serialize(response))
                // 6. 构建 TextEvent
                .map(serialized -> TextEvent.custom()
                        .id(sessionId)
                        .event(Event.MESSAGE.code())
                        .data(serialized)
                        .build());
    }

    /**
     * 异步处理消息。
     *
     * @param context 消息处理上下文
     * @return 包含处理结果的响应式流
     */
    private Solo<MessageProcessingContext> processMessageAsync(MessageProcessingContext context) {
        return Solo.create(emitter -> {
            try {
                Object params = cast(context.getRequest().get("params"));
                Object result = context.getHandler().handle(params);
                context.setResult(result);
                emitter.emit(context);
            } catch (Exception e) {
                context.setException(e);
                emitter.emit(context);
            }
        });
    }

    /**
     * 处理错误响应。
     *
     * @param sessionId 会话 ID
     * @param request 原始请求
     * @param exception 异常信息
     */
    private void handleErrorResponse(String sessionId, Map<String, Object> request, Exception exception) {
        Object id = request.get("id");
        if (id != null) {
            JsonRpc.Response<Object> errorResponse = JsonRpc.createResponseWithError(id, exception.getMessage());
            String serialized = serializer.serialize(errorResponse);
            TextEvent textEvent = TextEvent.custom()
                    .id(sessionId)
                    .event(Event.MESSAGE.code())
                    .data(serialized)
                    .build();
            
            Emitter<TextEvent> emitter = emitters.get(sessionId);
            if (emitter != null) {
                emitter.emit(textEvent);
                log.info("Send error response. [message={}]", serialized);
            }
        }
    }

    /**
     * 工具变更通知的响应式版本。
     */
    @Override
    public void onToolsChanged() {
        JsonRpc.Notification notification = JsonRpc.createNotification(Method.NOTIFICATION_TOOLS_CHANGED.code());
        String serialized = serializer.serialize(notification);
        
        // 使用响应式流广播通知
        Choir.fromIterable(emitters.entrySet())
                .subscribeOn(ThreadPoolExecutors.io())
                .subscribe(
                    subscription -> log.debug("Broadcasting tools changed notification"),
                    (subscription, entry) -> {
                        String sessionId = entry.getKey();
                        Emitter<TextEvent> emitter = entry.getValue();
                        TextEvent textEvent = TextEvent.custom()
                                .id(sessionId)
                                .event(Event.MESSAGE.code())
                                .data(serialized)
                                .build();
                        emitter.emit(textEvent);
                        log.info("Send MCP notification: tools changed. [sessionId={}]", sessionId);
                    },
                    subscription -> log.debug("Tools changed notification broadcast completed"),
                    (subscription, exception) -> log.error("Failed to broadcast tools changed notification", exception)
                );
    }

    /**
     * 消息处理上下文，用于在流水线中传递处理状态。
     */
    private static class MessageProcessingContext {
        private final Map<String, Object> request;
        private final MessageHandler handler;
        private Object result;
        private Exception exception;

        public MessageProcessingContext(Map<String, Object> request, MessageHandler handler) {
            this.request = request;
            this.handler = handler;
        }

        public Map<String, Object> getRequest() {
            return request;
        }

        public MessageHandler getHandler() {
            return handler;
        }

        public Object getResult() {
            return result;
        }

        public void setResult(Object result) {
            this.result = result;
        }

        public Exception getException() {
            return exception;
        }

        public void setException(Exception exception) {
            this.exception = exception;
        }
    }
}