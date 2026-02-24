package com.opensam.gateway.repository

import com.opensam.gateway.entity.SystemSetting
import org.springframework.data.jpa.repository.JpaRepository

interface SystemSettingRepository : JpaRepository<SystemSetting, String>
