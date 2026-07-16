package top.hetao.shiyuanticketmp.platform.ssl.runtime;

public interface SslRuntimeController {

    boolean isHttps();

    SslRuntimeTransition enable(SslKeyStoreMaterial material) throws Exception;

    SslRuntimeTransition disable() throws Exception;

    SslRuntimeTransition reload(SslKeyStoreMaterial material) throws Exception;
}
