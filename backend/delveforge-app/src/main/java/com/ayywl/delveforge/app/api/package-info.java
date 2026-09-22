/**
 * REST / Interface Adapter，按资源拆分为子包。
 *
 * <pre>
 * userprofile/        User Profile 资源
 * softwareasset/      Software Asset 资源（含触发 Repository Analysis 的端点）
 * repositoryprofile/  Repository Profile 资源
 * evidence/           Evidence 的接口表示，被上面两个资源共用
 * system/             服务级连通性探针，不属于任何领域资源
 * </pre>
 *
 * <p>Controller 保持轻量：解析请求、校验协议层输入、调用 Application Use Case、
 * 映射结果为响应；不得承载领域规则或流程编排（RULE-ARCH-005）。
 *
 * <p>资源之间的映射放在被渲染的资源旁边（例如 {@code RepositoryProfileResponse.from}），
 * 而不是让一个 Controller 提供另一个资源要用的方法。
 */
package com.ayywl.delveforge.app.api;
