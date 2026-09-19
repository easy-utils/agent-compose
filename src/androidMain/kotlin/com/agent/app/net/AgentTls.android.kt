package com.agent.app.net

import okhttp3.OkHttpClient

// Android actual: a plain OkHttp client. Certificate trust is provided by the
// platform TrustManager, which honors res/xml/network_security_config.xml
// (base-config trust-anchors: system + @raw/agent_ca). This is the official
// Android mechanism for a custom / non-public CA and deliberately avoids any
// custom TrustManager (see the expect declaration for why).
actual fun buildAgentOkHttpClient(): OkHttpClient = OkHttpClient()
