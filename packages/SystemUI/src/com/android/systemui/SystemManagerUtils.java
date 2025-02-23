/*
 * Copyright (C) 2019 Descendant
 * Copyright (C) 2023 the RisingOS Android Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlarmManager;
import android.app.AlarmManager.AlarmClockInfo;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.IPackageDataObserver;
import android.hardware.power.Boost;
import android.hardware.power.Mode;
import android.location.LocationManager;
import android.os.Handler;
import android.os.UserHandle;
import android.os.PowerManager;
import android.os.PowerManagerInternal;
import android.provider.Settings;

import com.android.internal.util.rising.systemUtils.SystemManagerController;
import com.android.server.LocalServices;
import com.android.systemui.Dependency;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class SystemManagerUtils {
    static String TAG = "SystemManagerUtils";

    static Handler h = new Handler();
    static Runnable mStartManagerInstance;
    static Runnable mStopManagerInstance;
    static PowerManagerInternal mLocalPowerManager;
    static SystemManagerController mSysManagerController;
    static List<ActivityManager.RunningAppProcessInfo> RunningServices;
    static final long IDLE_TIME_NEEDED = 20000;

    private static final Set<String> essentialProcesses = new HashSet<>(Arrays.asList(
            "com.android.",
            "org.rising",
            "android",
            "launcher",
            "ims",
            "dialer",
            "telepho",
            "sms",
            "messag"
    ));

    public static void initSystemManager(Context context) {
   	mSysManagerController = new SystemManagerController(context);
   	mLocalPowerManager = LocalServices.getService(PowerManagerInternal.class);

        mStartManagerInstance = new Runnable() {
            public void run() {
                idleModeHandler(true);
            }
        };

        mStopManagerInstance = new Runnable() {
            public void run() {
                cancelIdleService();
            }
        };
    }

    public static void startIdleService(Context context) {
        long nextAlarmTime = timeBeforeAlarm(context);
        if (nextAlarmTime == 0) {
            // No next alarm, start the service immediately.
            h.post(mStartManagerInstance);
        } else if (nextAlarmTime > IDLE_TIME_NEEDED) {
            // Next alarm is after the idle time, start the service after the idle time.
            h.postDelayed(mStartManagerInstance, IDLE_TIME_NEEDED);
        } else {
            // Next alarm is before the idle time, start the service right away and stop it before the alarm goes off.
            h.post(mStartManagerInstance);
            h.postDelayed(mStopManagerInstance, nextAlarmTime - 900000);
        }
    }

    public static void cacheCleaner(PackageManager pm) {
        List<ApplicationInfo> apps = pm.getInstalledApplications(0);
        for (ApplicationInfo packageInfo : apps) {
            pm.deleteApplicationCacheFiles(packageInfo.packageName,null);
        }
    }

    public static void idleModeHandler(boolean idle) {
        if (mLocalPowerManager != null) {
          mLocalPowerManager.setPowerMode(Mode.DEVICE_IDLE, idle);
        }
    }

    public static void cancelIdleService() {
        h.removeCallbacks(mStartManagerInstance);
        onScreenWake();
    }

     public static void boostingServiceHandler(boolean enable, int boostingLevel) {
        if (mLocalPowerManager != null) {
            switch (boostingLevel) {
            	case 0:
              	    // reset power modes
                    mLocalPowerManager.setPowerMode(Mode.SUSTAINED_PERFORMANCE, false);
                    mLocalPowerManager.setPowerMode(Mode.INTERACTIVE, false);
                    mLocalPowerManager.setPowerMode(Mode.FIXED_PERFORMANCE, false);
                    break;
                case 1:
              	    // low
                    mLocalPowerManager.setPowerMode(Mode.SUSTAINED_PERFORMANCE, enable);
                    break;
                case 2:
              	    // moderate
                    mLocalPowerManager.setPowerMode(Mode.INTERACTIVE, enable);
                    break;
                case 3:
                    // agrressive
                    mLocalPowerManager.setPowerMode(Mode.FIXED_PERFORMANCE, enable);
                    break;
                default:
                    break;
          }
        }
     }

    public static void onScreenWake() {
        h.removeCallbacks(mStopManagerInstance);
        idleModeHandler(false);
    }

   public static void enterPowerSaveMode(Context context, boolean enable) {
        PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        if (powerManager != null) {
            powerManager.setAdaptivePowerSaveEnabled(enable);
        }
        if (enable) {
            if (mLocalPowerManager != null) {
                mLocalPowerManager.setPowerBoost(Boost.DISPLAY_UPDATE_IMMINENT, 500);
            }
        }
   }

    public static long timeBeforeAlarm(Context context) {
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) {
            return 0;
        }

        AlarmClockInfo nextAlarm = alarmManager.getNextAlarmClock();
        return nextAlarm == null ? 0 : nextAlarm.getTriggerTime() - System.currentTimeMillis();
    }

   private static boolean isEssentialProcess(String packageName) {
      return essentialProcesses.stream().anyMatch(packageName::startsWith);
   }

   private static void deleteAllAppCacheFiles(PackageManager pm) {
      List<PackageInfo> installedPackages = pm.getInstalledPackages(PackageManager.GET_META_DATA);
      for (PackageInfo packageInfo : installedPackages) {
          pm.deleteApplicationCacheFiles(packageInfo.packageName, null);
      }
   }

    public static void deepClean(Context context, PackageManager pm, boolean idle) {
        ActivityManager activityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        UsageStatsManager usageStatsManager = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);

        HashSet<String> packagesToRestrict = new HashSet<>();

        activityManager.getRunningAppProcesses().forEach(processInfo -> {
            try {
                PackageInfo packageInfo = pm.getPackageInfo(processInfo.processName, PackageManager.GET_META_DATA);
                String packageName = packageInfo.packageName.toLowerCase();

                // Check if the app is not a essential process
                if (!isEssentialProcess(packageName) && packageName.contains("camera") && packageName.contains("settings")) {
                    packagesToRestrict.add(packageName);
                }
            } catch (PackageManager.NameNotFoundException ignored) {
            }
        });

        packagesToRestrict.forEach(packageName -> {
                usageStatsManager.setAppStandbyBucket(packageName, idle ? UsageStatsManager.STANDBY_BUCKET_RESTRICTED : UsageStatsManager.STANDBY_BUCKET_RARE);
        });

        deleteAllAppCacheFiles(pm);
    }

   public static void killBackgroundProcesses(Context context) {
      ActivityManager activityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
      List<ActivityManager.RunningAppProcessInfo> runningProcesses = activityManager.getRunningAppProcesses();
      HashSet<String> processesToKill = new HashSet<>();

      runningProcesses.forEach(processInfo -> {
         for (String packageName : processInfo.pkgList) {
            String lowercasePackageName = packageName.toLowerCase();
            if (!essentialProcesses.contains(lowercasePackageName)
                    && lowercasePackageName.contains("camera")
                    && lowercasePackageName.contains("settings")) {
               processesToKill.add(processInfo.processName);
               break; // Break out of the inner loop if a match is found
            }
         }
      });

      processesToKill.forEach(activityManager::killBackgroundProcesses);
   }
}
