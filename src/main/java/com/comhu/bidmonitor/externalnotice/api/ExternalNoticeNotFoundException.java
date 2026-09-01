package com.comhu.bidmonitor.externalnotice.api;

/** 요청한 DB ID에 해당하는 외부공지가 없을 때 발생한다. */
public class ExternalNoticeNotFoundException extends RuntimeException {

    public ExternalNoticeNotFoundException(Long id) {
        super("외부공지를 찾을 수 없습니다: " + id);
    }
}
