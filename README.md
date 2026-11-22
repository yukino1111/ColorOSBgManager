# ColorOSBgManager

[![Xposed](https://img.shields.io/badge/-Xposed-green?style=flat&logo=Android&logoColor=white)](#)
[![GitHub all releases](https://img.shields.io/github/downloads/Xposed-Modules-Repo/io.github.yukino1111.colorosbgmanager/total?label=Downloads)](https://github.com/Xposed-Modules-Repo/io.github.yukino1111.colorosbgmanager/releases)

适配 ColorOS 16 的后台应用管理模块，支持对墓碑、自启与关联启动、电池/Doze、Athena 内存清理、CPU 限核、Doze 限网及广播限流按应用单独放行或管控。

模块仅作自用分享。

## 测试环境

> ColorOS 16

## 使用方法

1. 在 LSPosed 中启用模块
2. 勾选作用域
3. 重启设备

## 免责声明

- 修改系统后台调度、墓碑冻结、限核、断网与广播限流策略可能影响消息推送、应用保活或功耗表现，**请谨慎调整并自行评估影响；**
- **本项目仅供个人学习、研究与自用分享**，因使用本模块造成的任何设备故障、数据丢失或异常，**作者概不承担责任**。

## 致谢

本模块思路与部分拦截逻辑借鉴自以下开源项目：  
[io.github.marukon.nohansfreeze](https://github.com/Xposed-Modules-Repo/io.github.marukon.nohansfreeze)
