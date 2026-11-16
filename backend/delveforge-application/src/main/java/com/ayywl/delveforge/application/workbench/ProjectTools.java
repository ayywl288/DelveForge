package com.ayywl.delveforge.application.workbench;

import java.util.Map;

/** 本机用户查看软件清单、核对推广预览，或显式发布受信开发镜像。 */
public interface ProjectTools {
    Map<String, Object> inventory(String project);

    Map<String, Object> preview(String project, String name);

    Map<String, Object> publish(String project, String name, String plan);
}
