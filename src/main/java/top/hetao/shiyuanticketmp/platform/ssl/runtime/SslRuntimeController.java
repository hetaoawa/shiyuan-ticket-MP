package top.hetao.shiyuanticketmp.platform.ssl.runtime;

public interface SslRuntimeController {

    boolean isHttps();

    void enable(SslKeyStoreMaterial material) throws Exception;

    void disable() throws Exception;

    void reload(SslKeyStoreMaterial material) throws Exception;
}
