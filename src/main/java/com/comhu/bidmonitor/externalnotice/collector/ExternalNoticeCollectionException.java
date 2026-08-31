package com.comhu.bidmonitor.externalnotice.collector;

/**
 * 원격 호출 실패나 필수 HTML 구조 변경 때문에 수집 결과를 신뢰할 수 없을 때 발생한다.
 * 구조 변경을 공지 0건으로 오인하지 않도록 별도 예외로 구분한다.
 */
public class ExternalNoticeCollectionException extends RuntimeException {

    public ExternalNoticeCollectionException(String message) {
        super(message);
    }

    public ExternalNoticeCollectionException(String message, Throwable cause) {
        super(message, cause);
    }
}
