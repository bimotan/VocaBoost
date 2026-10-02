package com.vocabtrainer.ui.importing;

import com.vocabtrainer.domain.LookupOutcome;
import javafx.scene.control.ProgressIndicator;

/** What the add form and the lookup box show about a dictionary lookup that found nothing. */
final class LookupMessages {
    private LookupMessages() {
    }

    /**
     * One line that tells "the dictionaries do not have the word" apart from "the dictionaries
     * could not be asked", and says what the user can do about the latter.
     */
    static String headline(LookupOutcome outcome) {
        return switch (outcome) {
            case FOUND -> "";
            case NOT_FOUND -> "词条未找到。";
            case NETWORK_ERROR -> "无法连接在线词典，请检查网络后重试。";
            case TIMEOUT -> "在线词典响应超时，请稍后重试。";
            case AUTH_ERROR -> "词典拒绝了请求，请稍后重试；使用词典 API 时请检查 DICTIONARY_API_KEY。";
            case RATE_LIMITED -> "在线词典暂时限制了查询次数，请稍后重试。";
            case SERVICE_ERROR -> "词典服务出错，请稍后重试。";
            case BAD_RESPONSE -> "词典返回了无法识别的内容。";
            case INTERRUPTED -> "查词已取消。";
        };
    }

    /** A small spinning indicator, hidden until a lookup runs (see {@link #setBusy}). */
    static ProgressIndicator busyIndicator(String id) {
        ProgressIndicator indicator = new ProgressIndicator(0);
        indicator.setId(id);
        indicator.setPrefSize(20, 20);
        indicator.setMaxSize(20, 20);
        indicator.setVisible(false);
        return indicator;
    }

    /** Shows the indicator spinning, or hides it and stops its animation. */
    static void setBusy(ProgressIndicator indicator, boolean busy) {
        indicator.setProgress(busy ? ProgressIndicator.INDETERMINATE_PROGRESS : 0);
        indicator.setVisible(busy);
    }
}
