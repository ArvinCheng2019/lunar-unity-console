//
//  ConsolePlugin.java
//
//  Lunar Unity Mobile Console
//  https://github.com/SpaceMadness/lunar-unity-console
//
//  Copyright 2017 Alex Lementuev, SpaceMadness.
//
//  Licensed under the Apache License, Version 2.0 (the "License");
//  you may not use this file except in compliance with the License.
//  You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
//  Unless required by applicable law or agreed to in writing, software
//  distributed under the License is distributed on an "AS IS" BASIS,
//  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//  See the License for the specific language governing permissions and
//  limitations under the License.
//

package spacemadness.com.lunarconsole.console;

import android.app.Activity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.FrameLayout;

import com.unity3d.player.UnityPlayer;

import java.lang.ref.WeakReference;
import java.util.List;
import java.util.Map;

import spacemadness.com.lunarconsole.R;
import spacemadness.com.lunarconsole.console.actions.Action;
import spacemadness.com.lunarconsole.console.actions.LUActionRegistry;
import spacemadness.com.lunarconsole.console.actions.LUCVar;
import spacemadness.com.lunarconsole.console.actions.LUCVarType;
import spacemadness.com.lunarconsole.core.Destroyable;
import spacemadness.com.lunarconsole.core.Notification;
import spacemadness.com.lunarconsole.core.NotificationCenter;
import spacemadness.com.lunarconsole.debug.Assert;
import spacemadness.com.lunarconsole.debug.Log;
import spacemadness.com.lunarconsole.settings.PluginSettings;
import spacemadness.com.lunarconsole.ui.gestures.GestureRecognizer;
import spacemadness.com.lunarconsole.ui.gestures.GestureRecognizerFactory;
import spacemadness.com.lunarconsole.utils.DictionaryUtils;

import static android.widget.FrameLayout.LayoutParams;
import static spacemadness.com.lunarconsole.console.Console.Options;
import static spacemadness.com.lunarconsole.console.ConsoleLogType.*;
import static spacemadness.com.lunarconsole.utils.ThreadUtils.*;
import static spacemadness.com.lunarconsole.utils.UIUtils.*;
import static spacemadness.com.lunarconsole.ui.gestures.GestureRecognizer.OnGestureListener;
import static spacemadness.com.lunarconsole.debug.Tags.*;
import static spacemadness.com.lunarconsole.console.ConsoleNotifications.*;
import static spacemadness.com.lunarconsole.utils.ObjectUtils.*;

public class ConsolePlugin implements Destroyable
{
    private static final String SCRIPT_MESSAGE_CONSOLE_OPEN  = "console_open";
    private static final String SCRIPT_MESSAGE_CONSOLE_CLOSE = "console_close";
    private static final String SCRIPT_MESSAGE_ACTION        = "console_action";
    private static final String SCRIPT_MESSAGE_VARIABLE_SET  = "console_variable_set";

    private static ConsolePlugin instance;

    private Console console;
    private final LUActionRegistry actionRegistry;
    private final ConsolePluginImp pluginImp;
    private final String version;
    private final PluginSettings settings;
    private final ConsoleViewState consoleViewState;

    /**
     * Parent container for console view (we need it to display additional overlays)
     */
    private FrameLayout consoleContentView;

    private ConsoleView consoleView;

    // private ConsoleLogView consoleLogView;
    private ConsoleOverlayLogView consoleOverlayLogView;
    private WarningView warningView;

    private final WeakReference<Activity> activityRef;
    private final GestureRecognizer gestureDetector;

    ////////////////////////////////////////////////////////////////////////////////////////////////
    // Static initialization

    /* Since the game can log many console entries on the secondary thread, we need an efficient
     * way to batch them on the main thread
     */
    private static final ConsoleLogEntryDispatcher entryDispatcher = new ConsoleLogEntryDispatcher(
            new ConsoleLogEntryDispatcher.OnDispatchListener()
            {
                @Override
                public void onDispatchEntries(List<ConsoleLogEntry> entries)
                {
                    if (instance != null)
                    {
                        instance.logEntries(entries);
                    }
                    else
                    {
                        Log.e("Can't log message: plugin instance is not initialized");
                    }
                }
            });

    ////////////////////////////////////////////////////////////////////////////////////////////////
    // Lifecycle

    /**
     * Data holder for plugin initialization
     */
    private static class UnitySettings
    {
        public final ConsolePluginImp pluginImp;
        public final String version;
        public final int capacity;
        public final int trim;
        public final String gesture;

        UnitySettings(ConsolePluginImp pluginImp, String version, int capacity, int trim, String gesture)
        {
            this.pluginImp = notNull(pluginImp, "Plugin implementation");
            this.gesture = notNullAndNotEmpty(gesture, "Gesture");
            this.version = notNullAndNotEmpty(version, "Version");
            this.capacity = positive(capacity, "Capacity");
            this.trim = positive(trim, "Trim");
        }

        private static <T> T notNull(T reference, String name)
        {
            if (reference == null)
            {
                throw new NullPointerException(name + " is null");
            }
            return reference;
        }

        private static String notNullAndNotEmpty(String reference, String name)
        {
            if (reference == null)
            {
                throw new NullPointerException(name + " is null");
            }
            if (reference.length() == 0)
            {
                throw new IllegalArgumentException(name + " is empty");
            }

            return reference;
        }

        private static int positive(int value, String name)
        {
            if (value <= 0)
            {
                throw new IllegalArgumentException(name + " should be positive but was " + value);
            }
            return value;
        }
    }

    public static void init(Activity activity, String version, int capacity, int trim, String gesture)
    {
        init(activity, new UnitySettings(new DefaultPluginImp(activity), version, capacity, trim, gesture));
    }

    private static void init(final Activity activity, final UnitySettings unitySettings)
    {
        if (isRunningOnMainThread())
        {
            init0(activity, unitySettings);
        }
        else
        {
            Log.d(PLUGIN,
                    "Tried to initialize plugin on the secondary thread. Scheduling on UI-thread...");

            runOnUIThread(new Runnable()
            {
                @Override
                public void run()
                {
                    init0(activity, unitySettings);
                }
            });
        }
    }

    private static void init0(Activity activity, UnitySettings unitySettings)
    {
        try
        {
            if (instance == null)
            {
                Log.d(PLUGIN, "Initializing plugin instance (%s): %d", unitySettings.version, unitySettings.capacity);
                instance = new ConsolePlugin(activity, unitySettings);
                instance.start();
            }
            else
            {
                Log.w("Plugin instance already initialized");
            }
        }
        catch (Exception e)
        {
            Log.e(e, "Can't initialize plugin instance");
        }
    }

    private void start()
    {
        enableGestureRecognition();

        if (settings.isEnableTransparentLogOverlay())
        {
            runOnUIThread(new Runnable()
            {
                @Override
                public void run()
                {
                    showLogOverlayView();
                }
            });
        }
    }

    public static void shutdown()
    {
        if (isRunningOnMainThread())
        {
            shutdown0();
        }
        else
        {
            Log.d(PLUGIN, "Tried to shutdown plugin on the secondary thread. Scheduling on UI-thread...");

            runOnUIThread(new Runnable()
            {
                @Override
                public void run()
                {
                    shutdown0();
                }
            });
        }
    }

    private static void shutdown0()
    {
        try
        {
            if (instance != null)
            {
                instance.destroy();
                instance = null;
            }
        }
        catch (Exception e)
        {
            Log.e(e, "Error while shutting down the plugin");
        }
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////
    // Unity native methods

    /**
     * This method is called by a Unity managed code. Do not rename or change params types of order
     *
     * @param targetName - the name of game object which would receive native callbacks
     * @param methodName - the name of the method of the game object to be called
     * @param version    - the plugin version
     * @param capacity   - the console`s capacity (everything beyond that would be trimmed)
     * @param trim       - the trim amount upon console overflow (how many items would be trimmed when console overflows)
     * @param gesture    - the name of a touch gesture to open the console or "none" if disabled
     */
    public static void init(String targetName, String methodName, String version, int capacity, int trim, String gesture)
    {
        Activity activity = UnityPlayer.currentActivity;
        init(activity, new UnitySettings(new UnityPluginImp(activity, targetName, methodName), version, capacity, trim, gesture));
    }

    public static void logMessage(String message, String stackTrace, int logType)
    {
        entryDispatcher.add(new ConsoleLogEntry((byte) logType, message, stackTrace));
    }

    public static void show()
    {
        if (isRunningOnMainThread())
        {
            show0();
        }
        else
        {
            runOnUIThread(new Runnable()
            {
                @Override
                public void run()
                {
                    show0();
                }
            });
        }
    }

    public static void hide()
    {
        if (isRunningOnMainThread())
        {
            hide0();
        }
        else
        {
            runOnUIThread(new Runnable()
            {
                @Override
                public void run()
                {
                    hide0();
                }
            });
        }
    }

    public static void clear()
    {
        if (isRunningOnMainThread())
        {
            clear0();
        }
        else
        {
            runOnUIThread(new Runnable()
            {
                @Override
                public void run()
                {
                    clear0();
                }
            });
        }
    }

    public static void registerAction(final int actionId, final String actionName)
    {
        if (isRunningOnMainThread())
        {
            registerAction0(actionId, actionName);
        }
        else
        {
            runOnUIThread(new Runnable()
            {
                @Override
                public void run()
                {
                    registerAction0(actionId, actionName);
                }
            });
        }
    }

    public static void unregisterAction(final int actionId)
    {
        if (isRunningOnMainThread())
        {
            unregisterAction0(actionId);
        }
        else
        {
            runOnUIThread(new Runnable()
            {
                @Override
                public void run()
                {
                    unregisterAction0(actionId);
                }
            });
        }
    }

    public static void registerVariable(final int variableId, final String name, final String type, final String value, final String defaultValue)
    {
        if (isRunningOnMainThread())
        {
            registerVariable0(variableId, name, type, value, defaultValue);
        }
        else
        {
            runOnUIThread(new Runnable()
            {
                @Override
                public void run()
                {
                    registerVariable0(variableId, name, type, value, defaultValue);
                }
            });
        }
    }

    public static void updateVariable(final int variableId, final String value)
    {
        if (isRunningOnMainThread())
        {
            updateVariable0(variableId, value);
        }
        else
        {
            runOnUIThread(new Runnable()
            {
                @Override
                public void run()
                {
                    updateVariable0(variableId, value);
                }
            });
        }
    }

    private static void show0()
    {
        if (instance != null)
        {
            instance.showConsole();
        }
        else
        {
            Log.w("Can't show console: instance is not initialized");
        }
    }

    private static void hide0()
    {
        if (instance != null)
        {
            instance.hideConsole();
        }
        else
        {
            Log.w("Can't hide console: instance is not initialized");
        }
    }

    private static void clear0()
    {
        if (instance != null)
        {
            instance.clearConsole();
        }
    }

    private static void registerAction0(int actionId, String actionName)
    {
        if (instance != null)
        {
            instance.actionRegistry.registerAction(actionId, actionName);
        }
    }

    private static void unregisterAction0(int actionId)
    {
        if (instance != null)
        {
            instance.actionRegistry.unregisterAction(actionId);
        }
    }

    private static void registerVariable0(int variableId, String name, String typeName, String value, String defaultValue)
    {
        if (instance != null)
        {
            LUCVarType type = LUCVarType.parse(typeName);
            if (type == LUCVarType.Unknown)
            {
                Log.e("Unexpected variable type: %s", typeName);
                return;
            }

            instance.actionRegistry.registerVariable(variableId, name, type, value, defaultValue);
        }
    }

    private static void updateVariable0(int variableId, String value)
    {
        if (instance != null)
        {
            instance.actionRegistry.updateVariable(variableId, value);
        }
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////
    // Overlay

    public static boolean isOverlayShown()
    {
        return instance != null && instance.isOverlayViewShown();
    }

    public static void showOverlay()
    {
        if (isRunningOnMainThread())
        {
            showOverlay0();
        }
        else
        {
            runOnUIThread(new Runnable()
            {
                @Override
                public void run()
                {
                    showOverlay0();
                }
            });
        }
    }

    private static void showOverlay0()
    {
        if (instance != null)
        {
            instance.showLogOverlayView();
        }
        else
        {
            Log.w("Can't show overlay: instance is not initialized");
        }
    }

    public static void hideOverlay()
    {
        if (isRunningOnMainThread())
        {
            hideOverlay0();
        }
        else
        {
            runOnUIThread(new Runnable()
            {
                @Override
                public void run()
                {
                    hideOverlay0();
                }
            });
        }
    }

    private static void hideOverlay0()
    {
        if (instance != null)
        {
            instance.hideLogOverlayView();
        }
        else
        {
            Log.w("Can't hide overlay: instance is not initialized");
        }
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////
    // Constructor

    private ConsolePlugin(Activity activity, UnitySettings unitySettings)
    {
        if (activity == null)
        {
            throw new NullPointerException("Context is null");
        }

        settings = new PluginSettings(activity.getApplicationContext());

        this.version = unitySettings.version;
        this.pluginImp = unitySettings.pluginImp;

        consoleViewState = new ConsoleViewState(activity.getApplicationContext());

        Options options = new Options(unitySettings.capacity);
        options.setTrimCount(unitySettings.trim);
        console = new Console(options);
        actionRegistry = new LUActionRegistry();

        activityRef = new WeakReference<>(activity);

        gestureDetector = GestureRecognizerFactory.create(activity, unitySettings.gesture);
        gestureDetector.setListener(new OnGestureListener()
        {
            @Override
            public void onGesture(GestureRecognizer gestureRecognizer)
            {
                showConsole();
            }
        });

        registerNotifications();
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////
    // Destroyable

    @Override
    public void destroy()
    {
        disableGestureRecognition();
        unregisterNotifications();

        console.destroy();
        entryDispatcher.cancelAll();

        Log.d(PLUGIN, "Plugin destroyed");
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////
    // Console

    private void logEntries(List<ConsoleLogEntry> entries)
    {
        for (int i = 0; i < entries.size(); ++i)
        {
            ConsoleLogEntry entry = entries.get(i);

            // add to console
            console.logMessage(entry);

            // show warning
            if (isErrorType(entry.type) && !isConsoleShown())
            {
                showWarning(entry.message);
            }
        }
    }

    private boolean showConsole()
    {
        hideLogOverlayView();

        try
        {
            if (consoleView == null)
            {
                Log.d(CONSOLE, "Show console");

                final Activity activity = getActivity();
                if (activity == null)
                {
                    Log.e("Can't show console: activity reference is lost");
                    return false;
                }

                // get root content layout
                final FrameLayout rootLayout = getRootLayout(activity);

                // we need to add an additional layout between console view and content view
                // in order to be able to place additional overlay layouts on top of everything
                consoleContentView = new FrameLayout(activity);
                rootLayout.addView(consoleContentView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

                // create console view
                consoleView = new ConsoleView(activity, this);
                consoleView.setListener(new ConsoleView.Listener()
                {
                    @Override
                    public void onOpen(ConsoleView view)
                    {
                        sendNativeCallback(SCRIPT_MESSAGE_CONSOLE_OPEN);
                    }

                    @Override
                    public void onClose(ConsoleView view)
                    {
                        hideConsole();
                        sendNativeCallback(SCRIPT_MESSAGE_CONSOLE_CLOSE);
                    }
                });

                // place console log view into console layout
                LayoutParams params = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);

                // set layout margins
                params.topMargin = consoleViewState.getTopMargin();
                params.bottomMargin = consoleViewState.getBottomMargin();
                params.leftMargin = consoleViewState.getLeftMargin();
                params.rightMargin = consoleViewState.getRightMargin();

                // add view
                consoleContentView.addView(consoleView, params);

                // show animation
                Animation animation = AnimationUtils.loadAnimation(activity, R.anim.lunar_console_slide_in_top);
                consoleView.startAnimation(animation);

                // notify delegates
                consoleView.notifyOpen();

                // don't handle gestures if console is shown
                disableGestureRecognition();

                // request focus
                consoleView.requestFocus();

                return true;
            }

            Log.w("Console is show already");
            return false;
        }
        catch (Exception e)
        {
            Log.e(e, "Can't show console");
            return false;
        }
    }

    private boolean hideConsole()
    {
        try
        {
            if (consoleView != null)
            {
                Log.d(CONSOLE, "Hide console");

                Activity activity = getActivity();
                if (activity != null)
                {
                    Animation animation = AnimationUtils.loadAnimation(activity, R.anim.lunar_console_slide_out_top);
                    animation.setAnimationListener(new Animation.AnimationListener()
                    {
                        @Override
                        public void onAnimationStart(Animation animation)
                        {
                        }

                        @Override
                        public void onAnimationEnd(Animation animation)
                        {
                            removeConsoleView();

                            if (settings.isEnableTransparentLogOverlay())
                            {
                                showLogOverlayView();
                            }
                        }

                        @Override
                        public void onAnimationRepeat(Animation animation)
                        {

                        }
                    });
                    consoleView.startAnimation(animation);
                }
                else
                {
                    removeConsoleView();
                }

                return true;
            }
        }
        catch (Exception e)
        {
            Log.e(e, "Can't hide console");
        }

        return false;
    }

    private void removeConsoleView()
    {
        if (consoleView != null)
        {
            // remove console log view from console content view
            consoleContentView.removeView(consoleView);

            // remove console content view from the root content view
            ViewParent parent = consoleContentView.getParent();
            if (parent instanceof ViewGroup)
            {
                ((ViewGroup) parent).removeView(consoleContentView);
            }
            else
            {
                Log.e("Can't remove console view: unexpected parent " + parent);
            }
            consoleContentView = null;

            consoleView.destroy();
            consoleView = null;

            // start listening for gestures
            enableGestureRecognition();
        }
    }

    private void clearConsole()
    {
        try
        {
            console.clear();
        }
        catch (Exception e)
        {
            Log.e(e, "Can't clear console");
        }
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////
    // Error warning

    private void showWarning(final String message)
    {
        try
        {
            if (!settings.isEnableExceptionWarning())
            {
                return;
            }

            if (warningView == null)
            {
                Log.d(WARNING_VIEW, "Show warning");

                final Activity activity = getActivity();
                if (activity == null)
                {
                    Log.e("Can't show warning: activity reference is lost");
                    return;
                }

                final FrameLayout rootLayout = getRootLayout(activity);

                warningView = new WarningView(activity);
                warningView.setListener(new WarningView.Listener()
                {
                    @Override
                    public void onDismissClick(WarningView view)
                    {
                        hideWarning();
                    }

                    @Override
                    public void onDetailsClick(WarningView view)
                    {
                        hideWarning();
                        showConsole();
                    }
                });

                rootLayout.addView(warningView);
            }

            warningView.setMessage(message);
        }
        catch (Exception e)
        {
            Log.e(e, "Can't show warning");
        }
    }

    private void hideWarning()
    {
        if (warningView != null)
        {
            Log.d(WARNING_VIEW, "Hide warning");

            ViewParent parent = warningView.getParent();
            if (parent instanceof ViewGroup)
            {
                ViewGroup parentGroup = (ViewGroup) parent;
                parentGroup.removeView(warningView);
            }
            else
            {
                Log.e("Can't hide warning view: unexpected parent view " + parent);
            }

            warningView.destroy();
            warningView = null;
        }
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////
    // Overlay view

    private boolean isOverlayViewShown()
    {
        return consoleOverlayLogView != null;
    }

    private boolean showLogOverlayView()
    {
        try
        {
            if (consoleOverlayLogView == null)
            {
                Log.d(OVERLAY_VIEW, "Show log overlay view");

                final Activity activity = getActivity();
                if (activity == null)
                {
                    Log.e("Can't show log overlay: activity reference is lost");
                    return false;
                }

                ConsoleOverlayLogView.Settings overlaySettings = new ConsoleOverlayLogView.Settings();

                final FrameLayout rootLayout = getRootLayout(activity);
                consoleOverlayLogView = new ConsoleOverlayLogView(activity, console, overlaySettings);

                LayoutParams params = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
                rootLayout.addView(consoleOverlayLogView, params);

                return true;
            }
        }
        catch (Exception e)
        {
            Log.e(e, "Can't show overlay view");
        }

        return false;
    }

    private boolean hideLogOverlayView()
    {
        try
        {
            if (consoleOverlayLogView != null)
            {
                Log.d(CONSOLE, "Hide log overlay view");

                Activity activity = getActivity();
                if (activity == null)
                {
                    Log.e("Can't hide log overlay view: activity reference is lost");
                    return false;
                }

                ViewParent parent = consoleOverlayLogView.getParent();
                if (parent instanceof ViewGroup)
                {
                    ((ViewGroup) parent).removeView(consoleOverlayLogView);
                }
                else
                {
                    Log.e("Can't remove overlay view: unexpected parent " + parent);
                    return false;
                }

                consoleOverlayLogView.destroy();
                consoleOverlayLogView = null;

                return true;
            }
        }
        catch (Exception e)
        {
            Log.e(e, "Can't hide log overlay view");
        }

        return false;
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////
    // Gesture recognition

    private void enableGestureRecognition()
    {
        Log.d(GESTURES, "Enable gesture recognition");

        View view = pluginImp.getTouchRecepientView();
        if (view == null)
        {
            Log.w("Can't enable gesture recognition: touch view is null");
            return;
        }

        view.setOnTouchListener(new View.OnTouchListener()
        {
            @Override
            public boolean onTouch(View v, MotionEvent event)
            {
                gestureDetector.onTouchEvent(event);
                return false; // do not block touch events!
            }
        });
    }

    private void disableGestureRecognition()
    {
        Log.d(GESTURES, "Disable gesture recognition");

        View view = pluginImp.getTouchRecepientView();
        if (view != null)
        {
            view.setOnTouchListener(null);
        }
        else
        {
            Log.w("Can't disable gesture recognition: touch view is null");
        }
    }

    private Activity getActivity()
    {
        return activityRef.get();
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////
    // Native callbacks

    private void sendNativeCallback(String name)
    {
        sendNativeCallback(name, null);
    }

    private void sendNativeCallback(String name, Map<String, Object> data)
    {
        pluginImp.sendUnityScriptMessage(name, data);
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////
    // Notifications

    private NotificationCenter.OnNotificationListener actionSelectListener;
    private NotificationCenter.OnNotificationListener variableSetListener;

    private void registerNotifications()
    {
        actionSelectListener = new NotificationCenter.OnNotificationListener()
        {
            @Override
            public void onNotification(Notification notification)
            {
                Action action = as(notification.getUserData(ACTION_SELECT_KEY_ACTION), Action.class);
                Assert.IsNotNull(action);

                if (action != null)
                {
                    sendNativeCallback(SCRIPT_MESSAGE_ACTION, DictionaryUtils.createMap("id", action.actionId()));
                }
            }
        };

        variableSetListener = new NotificationCenter.OnNotificationListener()
        {
            @Override
            public void onNotification(Notification notification)
            {
                LUCVar variable = as(notification.getUserData(VARIABLE_SET_KEY_VARIABLE), LUCVar.class);
                Assert.IsNotNull(variable);

                if (variable != null)
                {
                    Map<String, Object> params = DictionaryUtils.createMap(
                            "id", variable.actionId(),
                            "value", variable.value
                    );

                    sendNativeCallback(SCRIPT_MESSAGE_VARIABLE_SET, params);
                }
            }
        };

        NotificationCenter.defaultCenter().addListener(ACTION_SELECT, actionSelectListener);
        NotificationCenter.defaultCenter().addListener(VARIABLE_SET, variableSetListener);
    }

    private void unregisterNotifications()
    {
        if (actionSelectListener != null)
        {
            NotificationCenter.defaultCenter().removeListener(actionSelectListener);
            actionSelectListener = null;
        }

        if (variableSetListener != null)
        {
            NotificationCenter.defaultCenter().removeListener(variableSetListener);
            variableSetListener = null;
        }
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////
    // Getters/Setters

    Console getConsole()
    {
        return console;
    }

    ConsoleViewState getConsoleViewState()
    {
        return consoleViewState;
    }

    LUActionRegistry getActionRegistry()
    {
        return actionRegistry;
    }

    boolean isConsoleShown()
    {
        return consoleView != null;
    }

    public static PluginSettings pluginSettings()
    {
        return instance != null ? instance.settings : null;
    }

    public static String getVersion()
    {
        return instance != null ? instance.version : "?.?.?";
    }

    private static Console getConsoleInstance()
    {
        return instance.console;
    }

    public static void setCapacity(int capacity)
    {
        Options options = new Options(capacity);
        options.setTrimCount(getConsoleInstance().getTrimSize());
        instance.console = new Console(options);
    }

    public static void setTrimSize(int trimCount)
    {
        Options options = new Options(getConsoleInstance().getCapacity());
        options.setTrimCount(trimCount);
        instance.console = new Console(options);
    }
}
