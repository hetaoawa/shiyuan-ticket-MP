package top.hetao.shiyuanticketmp.platform.ssl.runtime;

/**
 * A reversible mutation of the live connector. The previous key store remains available until
 * {@link #commit()} so persistence and runtime state can be advanced as one compensating unit.
 */
public interface SslRuntimeTransition {

    void commit();

    void rollback() throws Exception;
}
