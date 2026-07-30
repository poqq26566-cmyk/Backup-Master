package com.example.p942818.backup;

interface IShellService {
    String exec(String command);
    /** 批量插入短信，jsonArray 是 [{address,body,date,type,read}, ...] 格式的JSON数组字符串
     *  返回 "OK:成功数:失败数" 或 "ERROR:错误信息" */
    String bulkInsertSms(String jsonArray);
    /** 批量插入通话记录，jsonArray 是 [{number,name,date,duration,type}, ...] 格式
     *  返回 "OK:成功数:失败数" 或 "ERROR:错误信息" */
    String bulkInsertCallLog(String jsonArray);
    void destroy();
}
