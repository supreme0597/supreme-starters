package club.supreme.framework.web.controller;

import club.supreme.framework.crud.conditions.Wraps;
import club.supreme.framework.crud.conditions.query.QueryWrap;
import club.supreme.framework.crud.page.PageParams;
import club.supreme.framework.model.response.R;
import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import io.swagger.annotations.ApiImplicitParam;
import io.swagger.annotations.ApiImplicitParams;
import io.swagger.annotations.ApiOperation;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.io.Serializable;
import java.util.List;

/**
 * 查询Controller
 *
 * @param <Entity>    实体
 * @param <Id>        主键
 * @param <PageQuery> 分页参数
 * @author supreme
 * @date 2020年03月07日22:06:35
 */
@SaCheckLogin(type = StpUtil.TYPE)
public interface QueryController<Entity, Id extends Serializable, PageQuery> extends PageController<Entity, PageQuery> {

    /**
     * 查询
     *
     * @param id 主键id
     * @return 查询结果
     */
    @ApiImplicitParams({
            @ApiImplicitParam(name = "id", value = "主键", dataType = "long", dataTypeClass = Long.class, paramType = "path"),
    })
    @ApiOperation(value = "单体查询", notes = "单体查询")
    @GetMapping("/{id}")
//    @SysLog("'查询:' + #id")
//    @PreAuth("hasAnyPermission('{}view')")
    default R<Entity> get(@PathVariable Id id) {
        return success(getBaseService().getById(id));
    }

    /**
     * 分页查询
     *
     * @param params 分页参数
     * @return 分页数据
     */
    @ApiOperation(value = "分页列表查询")
    @PostMapping(value = "/page")
//    @SysLog(value = "'分页列表查询:第' + #params?.current + '页, 显示' + #params?.size + '行'", response = false)
//    @PreAuth("hasAnyPermission('{}view')")
    default R<IPage<Entity>> page(@RequestBody @Validated PageParams<PageQuery> params) {
        return success(query(params));
    }

    /**
     * 批量查询
     *
     * @param data 批量查询
     * @return 查询结果
     */
    @ApiOperation(value = "批量查询", notes = "批量查询")
    @PostMapping("/query")
//    @SysLog("批量查询")
//    @PreAuth("hasAnyPermission('{}view')")
    default R<List<Entity>> query(@RequestBody Entity data) {
        QueryWrap<Entity> wrapper = Wraps.q(data);
        return success(getBaseService().list(wrapper));
    }

}
