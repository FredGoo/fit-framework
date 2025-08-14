# MCP 服务器 Choir 响应式流改造方案

## 概述

本文档描述了将 `McpServerController` 改造为基于 Choir 响应式流的实现方案。改造后的 `McpServerControllerReactive` 提供了更好的异步处理能力、错误处理机制和可组合的流水线架构。

## 改造优势

### 1. 非阻塞异步处理
- **原版本**：同步处理消息，可能阻塞请求线程
- **改造版本**：使用 Choir 响应式流，完全异步非阻塞处理

### 2. 流式错误处理
- **原版本**：使用 try-catch 进行错误处理
- **改造版本**：错误作为流的一部分传播，提供更细粒度的错误处理

### 3. 可组合的处理流水线
- **原版本**：处理逻辑耦合在单个方法中
- **改造版本**：将处理步骤分解为可组合的流操作

### 4. 更好的线程池利用
- **原版本**：在请求线程中执行所有操作
- **改造版本**：使用 `subscribeOn(ThreadPoolExecutors.io())` 在专用线程池中执行

## 核心改造点

### 1. 消息处理流水线

```java
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
```

### 2. 异步消息处理

```java
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
```

### 3. 响应式订阅模式

```java
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
```

### 4. 响应式广播通知

```java
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
```

## 处理上下文设计

为了在流水线中传递处理状态，引入了 `MessageProcessingContext` 类：

```java
private static class MessageProcessingContext {
    private final Map<String, Object> request;
    private final MessageHandler handler;
    private Object result;
    private Exception exception;
    
    // 构造函数和 getter/setter 方法
}
```

这个上下文类封装了：
- 原始请求数据
- 对应的消息处理器
- 处理结果
- 异常信息（如果有）

## 使用方式

### 1. 替换原控制器

将 `@Component` 注解从 `McpServerController` 移除，并添加到 `McpServerControllerReactive`：

```java
// 原版本 - 注释掉或删除 @Component
// @Component
public class McpServerController implements McpServer.ToolsChangedObserver {
    // ...
}

// 新版本 - 启用 @Component
@Component
public class McpServerControllerReactive implements McpServer.ToolsChangedObserver {
    // ...
}
```

### 2. 配置线程池（可选）

如果需要自定义线程池配置，可以创建专用的线程池：

```java
ThreadPoolExecutor customExecutor = ThreadPoolExecutors.custom()
        .corePoolSize(4)
        .maximumPoolSize(8)
        .threadPoolName("mcp-message-processor")
        .build();

// 在流水线中使用
.subscribeOn(customExecutor)
```

## 性能对比

| 特性 | 原版本 | 改造版本 |
|------|--------|----------|
| 处理模式 | 同步阻塞 | 异步非阻塞 |
| 线程利用 | 占用请求线程 | 专用线程池 |
| 错误处理 | try-catch | 流式错误传播 |
| 可扩展性 | 耦合度高 | 高度可组合 |
| 内存使用 | 较高（阻塞等待） | 较低（异步处理） |
| 吞吐量 | 受限于线程数 | 更高的并发处理能力 |

## 注意事项

1. **向后兼容性**：改造版本保持了相同的 HTTP API 接口，客户端无需修改

2. **错误处理**：响应式版本提供了更细粒度的错误处理，但需要确保所有异常都被正确捕获

3. **调试**：响应式流的调试可能比同步代码更复杂，建议增加详细的日志记录

4. **测试**：需要编写异步测试用例来验证响应式流的行为

## 总结

通过引入 Choir 响应式流，MCP 服务器的消息处理能力得到了显著提升：

- **性能提升**：非阻塞异步处理提高了系统吞吐量
- **代码质量**：流水线式的处理逻辑更清晰、更易维护
- **错误处理**：响应式错误传播机制更加健壮
- **可扩展性**：模块化的流操作便于功能扩展

这种改造方式充分利用了 FIT 框架的 Choir 响应式流能力，为 MCP 服务器提供了现代化的异步处理架构。