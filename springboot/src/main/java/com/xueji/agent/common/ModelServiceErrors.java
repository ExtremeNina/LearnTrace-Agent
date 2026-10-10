package com.xueji.agent.common;

/**
 * 模型服务错误识别（用户可读文案映射）：
 * DeepSeek 等服务方返回 HTTP 402 Insufficient Balance 时，任务应停止并提示充值，
 * 而不是静默降级（流水线继续走回退链路只会再次失败、追加出题只报"超时"掩盖真实原因）
 */
public final class ModelServiceErrors {

    private ModelServiceErrors() {
    }

    /** 余额不足时给用户的可读文案（流水线 error_msg / 推送通知 / 对话降级回复共用） */
    public static final String BALANCE_MESSAGE = "模型服务余额不足，请充值后重试";

    private static final String HTTP_402 = "HTTP 402";
    private static final String INSUFFICIENT_BALANCE = "Insufficient Balance";
    private static final String PAYMENT_REQUIRED = "402 Payment Required";

    /**
     * 沿异常消息链（含 cause，深度封顶防循环）匹配余额不足特征。
     * 纯函数，公开静态便于单测与多调用方复用
     */
    public static boolean isBalanceInsufficient(Throwable error) {
        Throwable current = error;
        int depth = 0;
        while (current != null && depth < 10) {
            if (matches(current.getMessage())) {
                return true;
            }
            current = current.getCause();
            depth++;
        }
        return false;
    }

    private static boolean matches(String message) {
        if (message == null) {
            return false;
        }
        return message.contains(HTTP_402) || message.contains(INSUFFICIENT_BALANCE)
                || message.contains(PAYMENT_REQUIRED);
    }
}
