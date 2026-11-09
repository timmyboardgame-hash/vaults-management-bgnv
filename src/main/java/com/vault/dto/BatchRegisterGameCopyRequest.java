package com.vault.dto;

import java.util.List;

/** ลงทะเบียนหลายกล่องในครั้งเดียว — ร้านยกชั้นวางเข้าระบบทีเดียว */
public record BatchRegisterGameCopyRequest(List<RegisterGameCopyRequest> copies) {}
