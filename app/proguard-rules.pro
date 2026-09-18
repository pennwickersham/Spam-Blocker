# The three components below are entry points instantiated by the system, not
# by app code, so R8 cannot see they are used. Without these keeps, a minified
# release build silently loses call screening and SMS handling.
-keep class com.spamblocker.app.service.CallBlockerService { *; }
-keep class com.spamblocker.app.service.SmsBlockerNotificationService { *; }
-keep class com.spamblocker.app.receiver.SmsSpamReceiver { *; }
