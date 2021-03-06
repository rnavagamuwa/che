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

import org.eclipse.che.ide.api.editor.EditorAgent;
import org.eclipse.che.ide.api.editor.EditorPartPresenter;
import org.eclipse.che.ide.api.editor.document.Document;
import org.eclipse.che.ide.api.editor.events.CursorActivityEvent;
import org.eclipse.che.ide.api.editor.events.CursorActivityHandler;
import org.eclipse.che.ide.api.editor.text.Position;
import org.eclipse.che.ide.api.editor.text.TextPosition;
import org.eclipse.che.ide.api.editor.texteditor.CursorModelWithHandler;
import org.eclipse.che.ide.api.editor.texteditor.TextEditor;
import org.eclipse.che.ide.resource.Path;
import org.eclipse.che.ide.socketio.Message;
import org.eclipse.che.ide.socketio.SocketOverlay;
import org.eclipse.che.ide.util.ListenerManager;
import org.eclipse.che.ide.util.ListenerRegistrar;

/**
 * Che product information constant.
 *
 * @author Randika Navagamuwa
 */
public class CursorModelForPairProgramming implements CursorModelWithHandler, CursorActivityHandler {
    private final Document document;
    private final ListenerManager<CursorModelWithHandler.CursorHandler> cursorHandlerManager = ListenerManager.create();
    private boolean isDocumentChanged = false;
    private SocketOverlay socket;
    private Path path;
    private EditorAgent editorAgent;
    private EditorPartPresenter openedEditor;
    private TextEditor textEditor;
    private String channelName;
    private String userId;
    private boolean isUpdatingModel = false;

    public CursorModelForPairProgramming(final Document document, SocketOverlay socket, EditorAgent editorAgent, String channelName, String userId) {
        this.document = document;
        this.document.addCursorHandler(this);
        this.socket = socket;
        this.editorAgent = editorAgent;
        this.channelName = channelName;
        this.userId = userId;
    }

    protected void documentCHanged(){
        this.isDocumentChanged = true;
    }

    @Override
    public void setCursorPosition(int offset) {
        TextPosition position = document.getPositionFromIndex(offset);
        document.setCursorPosition(position);
    }

    @Override
    public Position getCursorPosition() {
        TextPosition position = document.getCursorPosition();
        int offset = document.getIndexFromPosition(position);
        return new Position(offset);
    }

    @Override
    public ListenerRegistrar.Remover addCursorHandler(CursorModelWithHandler.CursorHandler handler) {
        return this.cursorHandlerManager.add(handler);
    }
    private void sendCursorPosition() {
        if (socket != null) {
            path = document.getFile().getLocation();
            openedEditor = editorAgent.getOpenedEditor(path);
            if (openedEditor instanceof TextEditor){
                textEditor  = (TextEditor) openedEditor;
            }
            int offset = textEditor.getCursorOffset();
            /*here withUserName method sets the channel name and the withchannelName sets the username*/
            Message liveResourceChangeMessage = new FluxMessageBuilder().with(document).withOffset(offset).withUserName(channelName).withChannelName(userId).buildLiveCursorOffsetChangeMessage();
            if (isUpdatingModel) {
                return;
            }
            socket.emit(liveResourceChangeMessage);
        }
    }

    @Override
    public void onCursorActivity(final CursorActivityEvent event) {

        if (isDocumentChanged){
            isDocumentChanged = false;
        } else{
            sendCursorPosition();
        }
    }
}
