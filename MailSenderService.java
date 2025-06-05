```java
package com.example.mailer.service;

import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class EmailService {

    // Фабрика для создания почтовых сервисов
    private final MailerServiceFactory mailerServiceFactory;
    // Обработчик исключений
    private final ExceptionHandler exceptionHandler;
    // Кэш, в который сохраняется статус сообщения
    private final Cache cache;
    // Конфигурация приложения (содержащая, например, TTL для статусов)
    private final AppConfig appConfig;
    // DI-контейнер для динамического создания классов
    private final ApplicationContext container;
    // Карта, содержащая мэппинг alias -> имя класса (полностью квалифицированное)
    private final Map<String, String> services;
    // Дефолтный почтовый сервис (если alias не указан)
    private final String defaultService;

    /**
     * Отправляет шаблонное письмо, используя соответствующий сервис.
     *
     * @param request объект с параметрами запроса
     * @return объект статуса отправки сообщения
     */
    public MessageStatusResponseDto sendTemplateEmail(TemplateEmailSendRequestDto request) {
        // Получаем почтовый сервис по alias "TEMPLATE"
        MailerContract service = mailerServiceFactory.create(MailerService.TEMPLATE());
        // Создаём статус сообщения
        MessageStatusResponseDto status = createMessageStatus(request);
        try {
            // Отправляем письмо
            service.send(request);
        } catch (Throwable e) {
            // Регистрируем ошибку
            exceptionHandler.report(e);
            // Обновляем статус как FAILED и возвращаем его
            return setMessageStatusFailed(status, e.getMessage());
        }
        // Обновляем статус как SENT и возвращаем его
        return setMessageStatusSent(status);
    }

    /**
     * Создает новый объект MessageStatusResponseDto для запроса.
     *
     * @param request исходный запрос (с информацией о канале и т.п.)
     * @return заполненный объект статуса, сохранённый в кэше
     */
    protected MessageStatusResponseDto createMessageStatus(AbstractMessageSendRequestDto request) {
        MessageStatusResponseDto status = new MessageStatusResponseDto();
        try {
            // Генерируем уникальный идентификатор
            status.setMessageId(UUID.randomUUID().toString());
        } catch (Throwable e) {
            throw CriticalException.fromThrowable(e, PostieErrorCode.INTERNAL_GUID_GENERATION_ERROR);
        }
        status.setChannel(request.getChannel());
        status.setStatus(Status.PENDING);  // Предполагается, что Status.PENDING - константа или значение enum
        status.setStatusUpdatedAt(new Date());
        storeStatus(status);
        return status;
    }

    /**
     * Сохраняет статус сообщения в кэше.
     *
     * @param status объект статуса, который необходимо сохранить
     */
    protected void storeStatus(MessageStatusResponseDto status) {
        String key = getCacheKey(status.getMessageId());
        // Получаем TTL из конфигурации по значению статуса
        int ttl = appConfig.getMessageStatusCache().getTtl(status.getStatus().getValue());
        // Очищаем существующее значение (если есть), затем устанавливаем новый статус
        cache.forget(key);
        cache.set(key, status, ttl);
    }

    /**
     * Фабричный метод для создания почтового сервиса.
     *
     * @param serviceAlias alias запрашиваемого сервиса.
     *                     Если null, используется дефолтный сервис.
     * @return инстанс, реализующий MailerContract
     */
    public MailerContract create(MailerService serviceAlias) {
        String serviceClassName = (serviceAlias != null)
                ? services.get(serviceAlias.getValue())
                : defaultService;
        if (serviceClassName == null || serviceClassName.isEmpty()) {
            throw new AppException(
                    PostieErrorCode.INTERNAL_MAILER_SERVICE_NOT_REGISTERED,
                    "serviceAlias: " + serviceAlias
            );
        }
        try {
            // Получаем класс по имени и возвращаем бин из контейнера
            Class<?> clazz = Class.forName(serviceClassName);
            return (MailerContract) container.getBean(clazz);
        } catch (ClassNotFoundException e) {
            throw new AppException(PostieErrorCode.INTERNAL_MAILER_SERVICE_NOT_REGISTERED, e);
        }
    }

    private MessageStatusResponseDto setMessageStatusFailed(MessageStatusResponseDto status, String errorMessage) {
        status.setStatus(Status.FAILED);
        status.setErrorMessage(errorMessage);
        status.setStatusUpdatedAt(new Date());
        storeStatus(status);
        return status;
    }

    private MessageStatusResponseDto setMessageStatusSent(MessageStatusResponseDto status) {
        status.setStatus(Status.SENT);
        status.setStatusUpdatedAt(new Date());
        storeStatus(status);
        return status;
    }

    /**
     * Формирует ключ для кеширования статуса по messageId.
     *
     * @param messageId идентификатор сообщения
     * @return строка-ключ для кеша
     */
    private String getCacheKey(String messageId) {
        return "message_status_" + messageId;
    }
}