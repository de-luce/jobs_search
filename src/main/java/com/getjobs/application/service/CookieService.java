package com.getjobs.application.service;

import com.getjobs.application.entity.CookieEntity;
import com.getjobs.application.mapper.CookieMapper;
import com.mybatisflex.core.query.QueryChain;
import com.mybatisflex.core.update.UpdateChain;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

import static com.getjobs.application.entity.table.CookieTableDef.COOKIE;

/**
 * Cookie服务类
 */
@Service
@RequiredArgsConstructor
public class CookieService {

    private final CookieMapper cookieMapper;

    /**
     * 根据平台获取Cookie
     * @param platform 平台名称（boss/zhilian/job51/liepin）
     * @return Cookie实体
     */
    public CookieEntity getCookieByPlatform(String platform) {
        return QueryChain.of(cookieMapper)
                .where(COOKIE.PLATFORM.eq(platform))
                .orderBy(COOKIE.UPDATED_AT.desc())
                .limit(1)
                .one();
    }

    /**
     * 保存或更新Cookie
     * @param platform 平台名称
     * @param cookieValue Cookie值
     * @param remark 备注
     * @return 是否成功
     */
    public boolean saveOrUpdateCookie(String platform, String cookieValue, String remark) {
        CookieEntity existingCookie = getCookieByPlatform(platform);

        if (existingCookie != null) {
            // 更新现有Cookie
            existingCookie.setCookieValue(cookieValue);
            existingCookie.setRemark(remark);
            existingCookie.setUpdatedAt(LocalDateTime.now());
            return cookieMapper.update(existingCookie) > 0;
        } else {
            // 新建Cookie
            CookieEntity newCookie = new CookieEntity();
            newCookie.setPlatform(platform);
            newCookie.setCookieValue(cookieValue);
            newCookie.setRemark(remark);
            newCookie.setCreatedAt(LocalDateTime.now());
            newCookie.setUpdatedAt(LocalDateTime.now());
            return cookieMapper.insert(newCookie) > 0;
        }
    }

    /**
     * 清空指定平台的所有Cookie值（处理重复记录场景）
     * @param platform 平台名称
     * @param remark 备注
     * @return 影响行数是否大于0
     */
    public boolean clearCookieByPlatform(String platform, String remark) {
        return UpdateChain.of(cookieMapper)
                .set(COOKIE.COOKIE_VALUE, "")
                .set(COOKIE.REMARK, remark)
                .set(COOKIE.UPDATED_AT, LocalDateTime.now())
                .where(COOKIE.PLATFORM.eq(platform))
                .update();
    }

    /**
     * 删除指定平台的Cookie
     * @param platform 平台名称
     * @return 是否成功
     */
    public boolean deleteCookie(String platform) {
        return UpdateChain.of(cookieMapper)
                .where(COOKIE.PLATFORM.eq(platform))
                .remove();
    }

    /**
     * 获取所有Cookie
     * @return Cookie列表
     */
    public List<CookieEntity> getAllCookies() {
        return cookieMapper.selectAll();
    }
}
