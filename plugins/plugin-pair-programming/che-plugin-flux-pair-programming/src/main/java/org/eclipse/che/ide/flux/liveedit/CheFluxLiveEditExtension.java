/*******************************************************************************
 * Copyright (c) 2012-2016 Codenvy, S.A.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *   Codenvy, S.A. - initial API and implementation
 *******************************************************************************/
package org.eclipse.che.ide.flux.liveedit;


import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.che.api.machine.shared.dto.MachineProcessDto;
import org.eclipse.che.api.machine.shared.dto.event.MachineProcessEvent;
import org.eclipse.che.api.promises.client.Operation;
import org.eclipse.che.api.promises.client.OperationException;
import org.eclipse.che.api.promises.client.Promise;
import org.eclipse.che.ide.api.app.AppContext;
import org.eclipse.che.ide.api.editor.EditorAgent;
import org.eclipse.che.ide.api.editor.EditorPartPresenter;
import org.eclipse.che.ide.api.editor.document.Document;
import org.eclipse.che.ide.api.editor.document.DocumentHandle;
import org.eclipse.che.ide.api.editor.events.*;
import org.eclipse.che.ide.api.editor.text.TextPosition;
import org.eclipse.che.ide.api.editor.text.TextRange;
import org.eclipse.che.ide.api.editor.texteditor.TextEditor;
import org.eclipse.che.ide.api.extension.Extension;
import org.eclipse.che.ide.api.machine.MachineServiceClient;
import org.eclipse.che.ide.api.macro.MacroProcessor;
import org.eclipse.che.ide.api.notification.NotificationManager;
import org.eclipse.che.ide.api.notification.StatusNotification;
import org.eclipse.che.ide.api.workspace.WorkspaceReadyEvent;
import org.eclipse.che.ide.rest.DtoUnmarshallerFactory;
import org.eclipse.che.ide.socketio.Consumer;
import org.eclipse.che.ide.socketio.Message;
import org.eclipse.che.ide.socketio.SocketIOOverlay;
import org.eclipse.che.ide.socketio.SocketIOResources;
import org.eclipse.che.ide.socketio.SocketOverlay;
import org.eclipse.che.ide.util.loging.Log;
import org.eclipse.che.ide.websocket.MessageBus;
import org.eclipse.che.ide.websocket.MessageBusProvider;
import org.eclipse.che.ide.websocket.WebSocketException;
import org.eclipse.che.ide.websocket.events.MessageHandler;
import org.eclipse.che.ide.websocket.rest.SubscriptionHandler;
import org.eclipse.che.ide.websocket.rest.Unmarshallable;
import com.google.gwt.dom.client.StyleInjector;

import com.google.gwt.core.client.JsonUtils;
import com.google.gwt.core.client.ScriptInjector;
import com.google.gwt.core.shared.GWT;
import com.google.gwt.user.client.Timer;
import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;

import org.eclipse.che.ide.resource.Path;

import static org.eclipse.che.ide.api.notification.ReadState.READ;
import static org.eclipse.che.ide.api.notification.StatusNotification.Status.SUCCESS;
import static org.eclipse.che.ide.api.notification.StatusNotification.DisplayMode.FLOAT_MODE;

/**
 * Che product information constant.
 *
 * @author Sun Seng David TAN
 * @author Randika Navagamuwa
 */

@Extension(title = "Che Flux extension", version = "1.0.0")
public class CheFluxLiveEditExtension{

    private Map<String, Document>                liveDocuments   = new HashMap<String, Document>();

    private SocketOverlay                        socket;

    private boolean                              isUpdatingModel = false;

    private MessageBus                           messageBus;

    private EventBus                             eventBus;

    private AppContext                           appContext;

    private MachineServiceClient                 machineServiceClient;

    private DtoUnmarshallerFactory               dtoUnmarshallerFactory;

    private EditorAgent editorAgent;
    private TextEditor textEditor;
    private EditorPartPresenter openedEditor;
    private Path path;
    private Document documentMain;
    private CursorHandlerForPairProgramming cursorHandlerForPairProgramming;
    private boolean isDocumentChanged = false;
    private NotificationManager notificationManager;
    private static Map<String,CursorHandlerForPairProgramming> cursorHandlers = new HashMap<String,CursorHandlerForPairProgramming>(); //if this is not static same user will have multiple cursor colours
    private static int userCount = 0;
    private static final String channelName = "USER";
    private String userId;
    private CursorModelForPairProgramming cursorModelForPairProgramming;

	private MacroProcessor macroProcessor;

    @Inject
    public CheFluxLiveEditExtension(final MessageBusProvider messageBusProvider,
                                    final EventBus eventBus,
                                    final MachineServiceClient machineServiceClient,
                                    final DtoUnmarshallerFactory dtoUnmarshallerFactory,
                                    final AppContext appContext,
                                    final MacroProcessor macroProcessor,
                                    final EditorAgent editorAgent,
                                    final NotificationManager notificationManager) {
        this.dtoUnmarshallerFactory = dtoUnmarshallerFactory;
        this.macroProcessor = macroProcessor;
        this.messageBus = messageBusProvider.getMessageBus();
        this.eventBus = eventBus;
        this.appContext = appContext;
        this.machineServiceClient = machineServiceClient;
        this.editorAgent = editorAgent;
        this.notificationManager = notificationManager;

        injectSocketIO();
        injectCssStyles();

        connectToFluxOnProjectLoaded();

        connectToFluxOnFluxProcessStarted();

        sendFluxMessageOnDocumentModelChanged();
    }


    private void injectCssStyles(){

        Map<Integer,String> colorMap = new HashMap<Integer,String>();
        colorMap.put(1,"#FFFF00");
        colorMap.put(2,"#00FF00");
        colorMap.put(3,"#00FFFF");
        colorMap.put(4,"#FF00FF");
        colorMap.put(5,"#FFFFFF");
        colorMap.put(6,"#C0C0C0");
        colorMap.put(7,"#808080");
        colorMap.put(8,"#FF0000");
        colorMap.put(9,"#800000");
        colorMap.put(10,"#808000");
        colorMap.put(11,"#008000");
        colorMap.put(12,"#008080");
        colorMap.put(13,"#0000FF");
        colorMap.put(14,"#800080");

        String css = null;
        for (int i =1 ; i<colorMap.size()+1;i++){
            css = ".pairProgramminigUser"+i+" { outline: 1px solid "+colorMap.get(i)+"; animation: blinker 1s linear infinite;} @keyframes blinker { 50% { opacity: 0.0; }}";
            StyleInjector.inject(css);
        }
    }

    private void injectSocketIO() {
        SocketIOResources ioresources = GWT.create(SocketIOResources.class);
        ScriptInjector.fromString(ioresources.socketIo().getText()).setWindow(ScriptInjector.TOP_WINDOW).inject();
    }

    private void connectToFluxOnProjectLoaded() {
        eventBus.addHandler(WorkspaceReadyEvent.getType(), new WorkspaceReadyEvent.WorkspaceReadyHandler() {
            @Override
            public void onWorkspaceReady(WorkspaceReadyEvent workspaceReadyEvent) {
                String machineId = appContext.getDevMachine().getId();
                String workspaceId = appContext.getWorkspaceId();
                Promise<List<MachineProcessDto>> processesPromise = machineServiceClient.getProcesses(workspaceId,machineId);
                processesPromise.then(new Operation<List<MachineProcessDto>>() {
                    @Override
                    public void apply(final List<MachineProcessDto> descriptors) throws OperationException {
                        if (descriptors.isEmpty()) {
                            return;
                        }
                        for (MachineProcessDto machineProcessDto : descriptors) {
                            if (connectIfFluxMicroservice(machineProcessDto)) {
                                break;
                            }
                        }
                    }
                });
            }
        });
    }

    private boolean connectIfFluxMicroservice(MachineProcessDto descriptor) {
        if (descriptor == null) {
            return false;
        }
        if ("flux".equals(descriptor.getName())) {
            String urlToSubstitute = "http://${server.port.3000}";
            substituteAndConnect(urlToSubstitute);
            return true;
        }
        return false;
    }

    int trySubstitude = 10;

    public void substituteAndConnect(final String previewUrl) {
            macroProcessor.expandMacros(previewUrl).then(new Operation<String>() {
                @Override
                public void apply(final String url) throws OperationException {
                    if (url.contains("$")){
                        Timer t = new Timer() {
                            @Override
                            public void run() {
                               substituteAndConnect(url);
                            }
                        };
                        Log.info(CheFluxLiveEditExtension.class,"Retrieving the preview url for " + url);
                        t.schedule(1000);
                        return;
                    }
                    connectToFlux(url);


                }
            });
    }

    int retryConnectToFlux = 5;

    protected void connectToFlux(final String url) {

        final SocketIOOverlay io = getSocketIO();
        Log.info(getClass(), "connecting to " + url);

        socket = io.connect(url);
        socket.on("error", new Runnable() {
            @Override
            public void run() {
                Log.info(getClass(), "error connecting to " + url);
            }
        });

        socket.on("liveResourceChanged", new Consumer<FluxResourceChangedEventDataOverlay>() {
            @Override
            public void accept(FluxResourceChangedEventDataOverlay event) {
                Document document = liveDocuments.get("/" + event.getProject() + "/" + event.getResource());
                if (document == null) {
                    return;
                }

                isUpdatingModel = true;
                path = document.getFile().getLocation();
                openedEditor = editorAgent.getOpenedEditor(path);
                if (openedEditor instanceof TextEditor){
                    textEditor  = (TextEditor) openedEditor;
                }

                String annotationStyle;
                String username = event.getChannelName();
                updateCursorHandler(username);

                cursorHandlerForPairProgramming = cursorHandlers.get(username);
                annotationStyle = "pairProgramminigUser"+ cursorHandlerForPairProgramming.getUserId();
                int offset = event.getOffset();

                if (openedEditor == null){
                    StatusNotification statusNotification = new StatusNotification(document.getFile().getLocation().toString()+" is being edited",SUCCESS,FLOAT_MODE);
                    statusNotification.setState(READ);
                    notificationManager.notify(statusNotification);
                    return;
                }
                if (event.getRemovedCharCount()==0){
                    offset ++;
                }
                String addedCharacters = event.getAddedCharacters();
                TextPosition cursorPosition = document.getCursorPosition();
                document.replace(event.getOffset(), event.getRemovedCharCount(), addedCharacters);
                document.setCursorPosition(cursorPosition);
                TextPosition markerPosition = textEditor.getDocument().getPositionFromIndex(offset);
                TextRange textRange = new TextRange(markerPosition, markerPosition);
                if (cursorHandlerForPairProgramming.getMarkerRegistration()!= null){
                    cursorHandlerForPairProgramming.clearMark();
                }
                cursorHandlerForPairProgramming.setMarkerRegistration(textEditor.getEditorWidget().addMarker(textRange,annotationStyle));
                cursorHandlers.remove(username);
                cursorHandlers.put(username,cursorHandlerForPairProgramming);
                isUpdatingModel = false;
            }
        });

        socket.on("liveCursorOffsetChanged", new Consumer<FluxResourceChangedEventDataOverlay>() {
            @Override
            public void accept(FluxResourceChangedEventDataOverlay event) {
                Document document = liveDocuments.get("/" + event.getProject() + "/" + event.getResource());
                if (document == null) {
                    return;
                }

                isUpdatingModel = true;
                path = document.getFile().getLocation();
                openedEditor = editorAgent.getOpenedEditor(path);
                if (openedEditor instanceof TextEditor){
                    textEditor  = (TextEditor) openedEditor;
                }

                String annotationStyle;
                String username = event.getChannelName();
                updateCursorHandler(username);

                cursorHandlerForPairProgramming = cursorHandlers.get(username);
                annotationStyle = "pairProgramminigUser"+ cursorHandlerForPairProgramming.getUserId();
                int offset = event.getOffset();
                /*if removed count equals to -100 that means there is only a cursor change */
                    TextPosition markerPosition = textEditor.getDocument().getPositionFromIndex(offset);
                    TextRange textRange = new TextRange(markerPosition, markerPosition);
                    if (cursorHandlerForPairProgramming.getMarkerRegistration()!= null){
                        cursorHandlerForPairProgramming.clearMark();
                    }
                    cursorHandlerForPairProgramming.setMarkerRegistration(textEditor.getEditorWidget().addMarker(textRange,annotationStyle));
                    cursorHandlers.remove(username);
                    cursorHandlers.put(username,cursorHandlerForPairProgramming);
                    isUpdatingModel = false;
            }
        });

        socket.emit("connectToChannel", JsonUtils.safeEval("{\"channel\" : \""+channelName+"\"}"));
    }

    private void updateCursorHandler(String username){
        if (cursorHandlers.get(username)==null){
            cursorHandlerForPairProgramming = new CursorHandlerForPairProgramming();
            cursorHandlerForPairProgramming.setUser(username);
            if (userCount==14){
                userCount =0;
            }
            userCount++;
            cursorHandlerForPairProgramming.setUserId(userCount);
            cursorHandlers.put(username,cursorHandlerForPairProgramming);
        }
    }

    public static native SocketIOOverlay getSocketIO()/*-{
                                                      return $wnd.io;
                                                      }-*/;

    private void connectToFluxOnFluxProcessStarted() {
         eventBus.addHandler(WorkspaceReadyEvent.getType(), new WorkspaceReadyEvent.WorkspaceReadyHandler() {
             @Override
             public void onWorkspaceReady(WorkspaceReadyEvent workspaceReadyEvent) {
                 String machineId = appContext.getDevMachine().getId();
                 final Unmarshallable<MachineProcessEvent> unmarshaller = dtoUnmarshallerFactory.newWSUnmarshaller(MachineProcessEvent.class);
                 final String processStateChannel = "machine:process:" + machineId;
                 final MessageHandler handler = new SubscriptionHandler<MachineProcessEvent>(unmarshaller) {
                     @Override
                     protected void onMessageReceived(MachineProcessEvent result) {
                         if (MachineProcessEvent.EventType.STARTED.equals(result.getEventType())) {
                             final int processId = result.getProcessId();
                             machineServiceClient.getProcesses(appContext.getWorkspaceId(),appContext.getDevMachine().getId()).then(new Operation<List<MachineProcessDto>>() {
                                 @Override
                                 public void apply(List<MachineProcessDto> descriptors) throws OperationException {
                                     if (descriptors.isEmpty()) {
                                         return;
                                     }

                                     for (final MachineProcessDto machineProcessDto : descriptors) {
                                         if (machineProcessDto.getPid() == processId) {
                                             new Timer() {
                                                 @Override
                                                 public void run() {
                                                     if (connectIfFluxMicroservice(machineProcessDto)) {
                                                         // break;
                                                     }
                                                 }
                                             }.schedule(8000);
                                             return;
                                         }
                                     }
                                 }

                             });
                         }
                     }

                     @Override
                     protected void onErrorReceived(Throwable exception) {
                         Log.error(getClass(), exception);
                     }
                 };
                 wsSubscribe(processStateChannel, handler);
             }
         });
    }

    private void wsSubscribe(String wsChannel, MessageHandler handler) {
        try {
            messageBus.subscribe(wsChannel, handler);
        } catch (WebSocketException e) {
            Log.error(getClass(), e);
        }
    }

    private void initCursorHandler(){
        if (socket!=null){
            cursorModelForPairProgramming = new CursorModelForPairProgramming(documentMain, socket, editorAgent, channelName, userId);
            return;
        }
        Timer t = new Timer() {
            @Override
            public void run() {
                initCursorHandler();
            }
        };
        t.schedule(1000);
    }

    private void sendFluxMessageOnDocumentModelChanged() {

        cursorHandlerForPairProgramming = new CursorHandlerForPairProgramming();
        eventBus.addHandler(DocumentReadyEvent.TYPE, new DocumentReadyHandler() {
            @Override
            public void onDocumentReady(DocumentReadyEvent event) {
                userId = "user" + Math.random();
                liveDocuments.put(event.getDocument().getFile().getLocation().toString(), event.getDocument());
                documentMain = event.getDocument();
                final DocumentHandle documentHandle = documentMain.getDocumentHandle();
                initCursorHandler();
                /*here withUserName method sets the channel name*/
                Message message = new FluxMessageBuilder().with(documentMain).withChannelName(userId).withUserName(channelName) //
                                                          .buildResourceRequestMessage();
                socket.emit(message);
                documentHandle.getDocEventBus().addHandler(DocumentChangeEvent.TYPE, new DocumentChangeHandler() {
                    @Override
                    public void onDocumentChange(DocumentChangeEvent event) {
                        if (socket != null) {
                            /*here withUserName method sets the channel name and the withchannelName sets the username*/
                            Message liveResourceChangeMessage = new FluxMessageBuilder().with(event).withUserName(channelName).withChannelName(userId)//
                                                                                        .buildLiveResourceChangeMessage();
                            isDocumentChanged = true;
                            if (isUpdatingModel) {
                                return;
                            }
                            socket.emit(liveResourceChangeMessage);

                        }
                    }
                });
            }
        });
    }
}
