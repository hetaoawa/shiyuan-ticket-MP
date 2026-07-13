package top.hetao.shiyuanticketmp.webhook.deadletter;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 死信记录 Mapper，继承 MyBatis-Plus BaseMapper 获得通用 CRUD 能力。
 *
 * <p>原有注解式 SQL 已移除，统一使用 BaseMapper 的通用方法。
 * 后续如需复杂查询（如分页查询 PENDING 状态记录），可使用 MyBatis-Plus 的 Wrapper 条件构造器。
 */
@Mapper
public interface WebhookDeadLetterMapper extends BaseMapper<WebhookDeadLetterRecord> {

    /**
     * Locks one tenant-visible, non-deleted row for the surrounding transaction.
     * The normal MyBatis tenant interceptor injects tenant_id; this method deliberately
     * has no interceptor bypass.
     */
    @Select("""
            SELECT *
            FROM webhook_dead_letter
            WHERE id = #{id}
              AND deleted = 0
            FOR UPDATE
            """)
    WebhookDeadLetterRecord selectByIdForUpdate(@Param("id") Long id);
}
