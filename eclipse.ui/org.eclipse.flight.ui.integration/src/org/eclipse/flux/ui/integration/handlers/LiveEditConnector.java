/*******************************************************************************
 * Copyright (c) 2013, 2014 Pivotal Software, Inc. and others.
 * All rights reserved. This program and the accompanying materials are made 
 * available under the terms of the Eclipse Public License v1.0 
 * (http://www.eclipse.org/legal/epl-v10.html), and the Eclipse Distribution 
 * License v1.0 (http://www.eclipse.org/org/documents/edl-v10.html). 
 *
 * Contributors:
 *     Pivotal Software, Inc. - initial API and implementation
*******************************************************************************/
package org.eclipse.flux.ui.integration.handlers;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.eclipse.core.filebuffers.FileBuffers;
import org.eclipse.core.filebuffers.IFileBuffer;
import org.eclipse.core.filebuffers.IFileBufferListener;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceChangeEvent;
import org.eclipse.core.resources.IResourceChangeListener;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.resources.IResourceDeltaVisitor;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.flux.core.ConnectedProject;
import org.eclipse.flux.core.ILiveEditConnector;
import org.eclipse.flux.core.IRepositoryListener;
import org.eclipse.flux.core.LiveEditCoordinator;
import org.eclipse.flux.core.Repository;
import org.eclipse.jface.text.DocumentEvent;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IDocumentListener;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.texteditor.AbstractTextEditor;

/**
 * @author Martin Lippert
 */
public class LiveEditConnector {
	
	private static final String LIVE_EDIT_CONNECTOR_ID = "UI-Editor-Live-Edit-Connector";
	
	private IDocumentListener documentListener;
	private Repository repository;
	
	private ConcurrentMap<IDocument, String> resourceMappings;
	private ConcurrentMap<String, IDocument> documentMappings;
	private LiveEditCoordinator liveEditCoordinator;

	private ConcurrentHashMap<String, PendingLiveEditStartedResponse> pendingLiveEditStartedResponses;

	public LiveEditConnector(LiveEditCoordinator liveEditCoordinator, Repository repository) {
		this.liveEditCoordinator = liveEditCoordinator;
		this.repository = repository;
		
		this.resourceMappings = new ConcurrentHashMap<IDocument, String>();
		this.documentMappings = new ConcurrentHashMap<String, IDocument>();
		
		this.pendingLiveEditStartedResponses = new ConcurrentHashMap<String, PendingLiveEditStartedResponse>();
		
		this.documentListener = new IDocumentListener() {
			@Override
			public void documentChanged(DocumentEvent event) {
				sendModelChangedMessage(event);
			}
			@Override
			public void documentAboutToBeChanged(DocumentEvent event) {
			}
		};
		
		FileBuffers.getTextFileBufferManager().addFileBufferListener(new IFileBufferListener() {
			@Override
			public void underlyingFileMoved(IFileBuffer buffer, IPath path) {
			}
			
			@Override
			public void underlyingFileDeleted(IFileBuffer buffer) {
			}
			
			@Override
			public void stateValidationChanged(IFileBuffer buffer, boolean isStateValidated) {
			}
			
			@Override
			public void stateChanging(IFileBuffer buffer) {
			}
			
			@Override
			public void stateChangeFailed(IFileBuffer buffer) {
			}
			
			@Override
			public void dirtyStateChanged(IFileBuffer buffer, boolean isDirty) {
			}

			@Override
			public void bufferDisposed(IFileBuffer buffer) {
			}

			@Override
			public void bufferCreated(IFileBuffer buffer) {
			}
			
			@Override
			public void bufferContentReplaced(IFileBuffer buffer) {
				IPath path = buffer.getLocation();

				IWorkspace workspace = ResourcesPlugin.getWorkspace();
				IWorkspaceRoot root = workspace.getRoot();
				IResource resource = root.findMember(path);
				if (resource != null) {
					IProject project = resource.getProject();
					String resourcePath = resource.getProjectRelativePath().toString();
					
					String fullPath = project.getName() + "/" + resourcePath;
					IDocument doc = documentMappings.get(fullPath);
					if (doc != null) {
						doc.addDocumentListener(documentListener);
					}
				}
				
				System.out.println("content replaced by new version on the file system");
			}
			
			@Override
			public void bufferContentAboutToBeReplaced(IFileBuffer buffer) {
				IPath path = buffer.getLocation();

				IWorkspace workspace = ResourcesPlugin.getWorkspace();
				IWorkspaceRoot root = workspace.getRoot();
				IResource resource = root.findMember(path);
				if (resource != null) {
					IProject project = resource.getProject();
					String resourcePath = resource.getProjectRelativePath().toString();
					
					String fullPath = project.getName() + "/" + resourcePath;
					IDocument doc = documentMappings.get(fullPath);
					if (doc != null) {
						doc.removeDocumentListener(documentListener);
					}
				}
			}
		});
		
		this.repository.addRepositoryListener(new IRepositoryListener() {
			@Override
			public void projectConnected(IProject project) {
				connectOpenEditors(project);
			}
			@Override
			public void projectDisconnected(IProject project) {
				disconnectOpenEditors(project);
			}
		});
		
		ILiveEditConnector liveEditConnector = new ILiveEditConnector() {
			@Override
			public String getConnectorID() {
				return LIVE_EDIT_CONNECTOR_ID;
			}

			@Override
			public void liveEditingEvent(String username, String resourcePath, int offset, int removeCount, String newText) {
				handleModelChanged(username, resourcePath, offset, removeCount, newText);
			}

			@Override
			public void liveEditingStarted(String requestSenderID, int callbackID, String username, String resourcePath, String hash, long timestamp) {
				remoteEditorStarted(requestSenderID, callbackID, username, resourcePath, hash, timestamp);
			}

			@Override
			public void liveEditingStartedResponse(String requestSenderID, int callbackID, String username, String projectName, String resourcePath,
					String savePointHash, long savePointTimestamp, String content) {
				handleRemoteLiveContent(requestSenderID, callbackID, username, projectName, resourcePath, savePointHash, savePointTimestamp, content);
			}
		};
		this.liveEditCoordinator.addLiveEditConnector(liveEditConnector);
		
		IWorkspace workspace = ResourcesPlugin.getWorkspace();
		workspace.addResourceChangeListener(new IResourceChangeListener() {
			@Override
			public void resourceChanged(IResourceChangeEvent event) {
				reactToResourceChanged(event);
			}
		});
		
		Display.getDefault().asyncExec(new Runnable() {
			public void run() {
				IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
				window.getActivePage().addPartListener(new IPartListener2() {
					@Override
					public void partVisible(IWorkbenchPartReference partRef) {
					}
					@Override
					public void partOpened(IWorkbenchPartReference partRef) {
						IWorkbenchPart part = partRef.getPart(false);
						if (part instanceof AbstractTextEditor) {
							connectEditor((AbstractTextEditor) part);
						}
					}
					@Override
					public void partInputChanged(IWorkbenchPartReference partRef) {
					}
					@Override
					public void partHidden(IWorkbenchPartReference partRef) {
					}
					@Override
					public void partDeactivated(IWorkbenchPartReference partRef) {
					}
					@Override
					public void partClosed(IWorkbenchPartReference partRef) {
						IWorkbenchPart part = partRef.getPart(false);
						if (part instanceof AbstractTextEditor) {
							disconnectEditor((AbstractTextEditor) part);
						}
					}
					@Override
					public void partBroughtToTop(IWorkbenchPartReference partRef) {
					}
					@Override
					public void partActivated(IWorkbenchPartReference partRef) {
					}
				});
			}
		});
	}
	
	protected void remoteEditorStarted(String requestSenderID, int callbackID, String username, String resourcePath, String hash, long timestamp) {
		// a different editor was started editing the resource, we need to send back live content
		
		if (this.repository.getUsername().equals(username) && documentMappings.containsKey(resourcePath)) {
			final IDocument document = documentMappings.get(resourcePath);
			String content = document.get();
			
			String projectName = resourcePath.substring(0, resourcePath.indexOf('/'));
			String relativeResourcePath = resourcePath.substring(projectName.length() + 1);
			
			this.liveEditCoordinator.sendLiveEditStartedResponse(LIVE_EDIT_CONNECTOR_ID, requestSenderID, callbackID, username, projectName, relativeResourcePath, hash, timestamp, content);
		}
	}

	protected void handleRemoteLiveContent(String requestSenderID, int callbackID, final String username, final String projectName, final String resource,
			final String savePointHash, final long savePointTimestamp, final String content) {
		// we started the editing and are getting remote live content back
		
		PendingLiveEditStartedResponse newResponse = new PendingLiveEditStartedResponse(username, projectName, resource, savePointHash, savePointTimestamp, content);
		handleRemoteLiveContent(newResponse);
	}
	
	protected void handleRemoteLiveContent(final PendingLiveEditStartedResponse pendingResponse) {
		
		final String resourcePath = pendingResponse.getProjectName() + "/" + pendingResponse.getResource();
		
		if (this.repository.getUsername().equals(pendingResponse.getUsername()) && documentMappings.containsKey(resourcePath)) {
			final IDocument document = documentMappings.get(resourcePath);

			try {
				Display.getDefault().asyncExec(new Runnable() {
					public void run() {
						try {
							ConnectedProject connectedProject = repository.getProject(pendingResponse.getProjectName());
							final String hash = connectedProject.getHash(pendingResponse.getResource());
							final long timestamp = connectedProject.getTimestamp(pendingResponse.getResource());
							
							if (hash != null && hash.equals(pendingResponse.getSavePointHash()) && timestamp == pendingResponse.getSavePointTimestamp()) {
								String openedContent = document.get();
								if (!openedContent.equals(pendingResponse.getContent())) {
									document.removeDocumentListener(documentListener);
									document.set(pendingResponse.getContent());
									document.addDocumentListener(documentListener);
								}
							}
							else if (pendingResponse.getSavePointTimestamp() > timestamp) {
								PendingLiveEditStartedResponse existingPendingRespose = pendingLiveEditStartedResponses.putIfAbsent(resourcePath, pendingResponse);
								if (pendingResponse.getSavePointTimestamp() > existingPendingRespose.getSavePointTimestamp()) {
									pendingLiveEditStartedResponses.put(resourcePath, pendingResponse);
								}
							}
						}
						catch (Exception e) {
							e.printStackTrace();
						}
					}
				});
			}
			catch (Exception e) {
				e.printStackTrace();
			}
		}

	}

	protected void handleModelChanged(final String username, final String resourcePath, final int offset, final int removedCharCount, final String newText) {
		if (repository.getUsername().equals(username) && resourcePath != null && documentMappings.containsKey(resourcePath)) {
			final IDocument document = documentMappings.get(resourcePath);
			
			try {
				Display.getDefault().asyncExec(new Runnable() {
					public void run() {
						try {
							document.removeDocumentListener(documentListener);
							document.replace(offset, removedCharCount, newText);
							document.addDocumentListener(documentListener);
						}
						catch (Exception e) {
							e.printStackTrace();
						}
					}
				});
			}
			catch (Exception e) {
				e.printStackTrace();
			}
		}
	}

	protected void sendModelChangedMessage(DocumentEvent event) {
		String resourcePath = resourceMappings.get(event.getDocument());
		if (resourcePath != null) {
			String projectName = resourcePath.substring(0, resourcePath.indexOf('/'));
			String relativeResourcePath = resourcePath.substring(projectName.length() + 1);

			this.liveEditCoordinator.sendModelChangedMessage(LIVE_EDIT_CONNECTOR_ID, repository.getUsername(), projectName, relativeResourcePath, event.getOffset(), event.getLength(), event.getText());
		}
	}

	protected void connectEditor(AbstractTextEditor texteditor) {
		final IDocument document = texteditor.getDocumentProvider().getDocument(texteditor.getEditorInput());
		IResource editorResource = (IResource) texteditor.getEditorInput().getAdapter(IResource.class);
		
		if (document != null && editorResource != null) {
			IProject project = editorResource.getProject();
			String projectName = project.getName();
			String resource = editorResource.getProjectRelativePath().toString();

			String resourcePath = projectName + "/" + resource;
			
			if (repository.isConnected(project)) {
				documentMappings.put(resourcePath, document);
				resourceMappings.put(document, resourcePath);

				document.addDocumentListener(documentListener);
				
				ConnectedProject connectedProject = repository.getProject(project);
				String hash = connectedProject.getHash(resource);
				long timestamp = connectedProject.getTimestamp(resource);
				
				this.liveEditCoordinator.sendLiveEditStartedMessage(LIVE_EDIT_CONNECTOR_ID, repository.getUsername(), projectName, resource, hash, timestamp);
			}
		}
	}
	
	protected void disconnectEditor(AbstractTextEditor texteditor) {
		final IDocument document = texteditor.getDocumentProvider().getDocument(texteditor.getEditorInput());
		
		String resourcePath = resourceMappings.get(document);
		if (resourcePath != null) {
			document.removeDocumentListener(documentListener);
			documentMappings.remove(resourcePath);
			resourceMappings.remove(document);
		}
	}

	protected void connectOpenEditors(IProject project) {
		Display.getDefault().asyncExec(new Runnable() {
			public void run() {
				IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
				IEditorReference[] editorReferences = window.getActivePage().getEditorReferences();
				for (IEditorReference editorReference : editorReferences) {
					IEditorPart editorPart = editorReference.getEditor(false);
					if (editorPart instanceof AbstractTextEditor) {
						connectEditor((AbstractTextEditor) editorPart);
					}
				}
			}
		});
	}
	
	protected void disconnectOpenEditors(IProject project) {
		Display.getDefault().asyncExec(new Runnable() {
			public void run() {
				IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
				IEditorReference[] editorReferences = window.getActivePage().getEditorReferences();
				for (IEditorReference editorReference : editorReferences) {
					IEditorPart editorPart = editorReference.getEditor(false);
					if (editorPart instanceof AbstractTextEditor) {
						disconnectEditor((AbstractTextEditor) editorPart);
					}
				}
			}
		});
	}

	public void reactToResourceChanged(IResourceChangeEvent event) {
		try {
			event.getDelta().accept(new IResourceDeltaVisitor() {
				@Override
				public boolean visit(IResourceDelta delta) throws CoreException {
					reactToResourceChanged(delta);
					return true;
				}
			});
		} catch (CoreException e) {
			e.printStackTrace();
		}
	}

	protected void reactToResourceChanged(IResourceDelta delta) {
		IProject project = delta.getResource().getProject();
		if (project != null) {
			if (repository.isConnected(project)) {
				reactToResourceChange(delta);
			}
		}
	}

	private void reactToResourceChange(IResourceDelta delta) {
		IResource resource = delta.getResource();

		if (resource != null && resource.isDerived(IResource.CHECK_ANCESTORS)) {
			return;
		}

		switch (delta.getKind()) {
		case IResourceDelta.ADDED:
			// TODO: cleanup
			break;
		case IResourceDelta.REMOVED:
			// TODO: cleanup
			break;
		case IResourceDelta.CHANGED:
			reactOnResourceChange(resource);
			break;
		}
	}

	private void reactOnResourceChange(IResource resource) {
		if (resource != null && resource instanceof IFile) {
			final ConnectedProject connectedProject = this.repository.getProject(resource.getProject());
			final String resourcePath = resource.getProjectRelativePath().toString();
			
			if (connectedProject != null && connectedProject.containsResource(resourcePath)) {
				String key = connectedProject.getName() + "/" + resourcePath;
				PendingLiveEditStartedResponse pendingResponse = pendingLiveEditStartedResponses.get(key);
				if (pendingResponse != null) {
					handleRemoteLiveContent(pendingResponse);
				}
			}
		}
	}

}
