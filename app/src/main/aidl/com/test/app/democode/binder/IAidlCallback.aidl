package com.test.app.democode.binder;

interface IAidlCallback {
    // 服务端回调客户端。这里用 oneway，表示服务端通知后不等待客户端处理完成。
    oneway void onResult(String message);
}
