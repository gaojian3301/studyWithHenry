package com.test.app.democode.binder;

import android.os.Bundle;
import com.test.app.democode.binder.IAidlCallback;

interface IDemoAidlInterface {
    // 普通同步调用：客户端会等待服务端执行完成并返回结果。
    String echo(String message);

    // oneway：单向异步调用，客户端发起后不等待服务端执行完成。
    // oneway 方法不能有返回值，也不能使用 out / inout 参数。
    oneway void sendOnewayMessage(String message);

    // in：客户端把 data 传给服务端，服务端可以读取。
    // 服务端即使修改 data，修改结果也不会回传给客户端。
    void setInput(in Bundle data);

    // out：客户端传一个容器给服务端，服务端负责写入数据。
    // 方法返回后，客户端可以读取服务端写入的内容。
    void fillOutput(out Bundle data);

    // inout：客户端先把 data 传给服务端，服务端可以读取并修改。
    // 方法返回后，客户端可以读取服务端修改后的内容。
    void updateInOut(inout Bundle data);

    // 客户端把自己的 callback Binder 注册到服务端。
    // 服务端后续可以通过这个 callback 反向通知客户端。
    void registerCallback(IAidlCallback callback);

    // 客户端不再需要回调时主动反注册。
    void unregisterCallback(IAidlCallback callback);
}
