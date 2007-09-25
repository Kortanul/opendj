/*
 * CDDL HEADER START
 *
 * The contents of this file are subject to the terms of the
 * Common Development and Distribution License, Version 1.0 only
 * (the "License").  You may not use this file except in compliance
 * with the License.
 *
 * You can obtain a copy of the license at
 * trunk/opends/resource/legal-notices/OpenDS.LICENSE
 * or https://OpenDS.dev.java.net/OpenDS.LICENSE.
 * See the License for the specific language governing permissions
 * and limitations under the License.
 *
 * When distributing Covered Code, include this CDDL HEADER in each
 * file and include the License file at
 * trunk/opends/resource/legal-notices/OpenDS.LICENSE.  If applicable,
 * add the following below this CDDL HEADER, with the fields enclosed
 * by brackets "[]" replaced with your own identifying information:
 *      Portions Copyright [yyyy] [name of copyright owner]
 *
 * CDDL HEADER END
 *
 *
 *      Portions Copyright 2007 Sun Microsystems, Inc.
 */
package org.opends.server.workflowelement.localbackend;



import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.locks.Lock;

import org.opends.messages.Message;
import org.opends.messages.MessageBuilder;
import org.opends.server.api.Backend;
import org.opends.server.api.ChangeNotificationListener;
import org.opends.server.api.ClientConnection;
import org.opends.server.api.SynchronizationProvider;
import org.opends.server.api.plugin.PostOperationPluginResult;
import org.opends.server.api.plugin.PreOperationPluginResult;
import org.opends.server.controls.LDAPAssertionRequestControl;
import org.opends.server.controls.LDAPPostReadRequestControl;
import org.opends.server.controls.LDAPPostReadResponseControl;
import org.opends.server.controls.LDAPPreReadRequestControl;
import org.opends.server.controls.LDAPPreReadResponseControl;
import org.opends.server.controls.ProxiedAuthV1Control;
import org.opends.server.controls.ProxiedAuthV2Control;
import org.opends.server.core.AccessControlConfigManager;
import org.opends.server.core.DirectoryServer;
import org.opends.server.core.ModifyDNOperation;
import org.opends.server.core.ModifyDNOperationWrapper;
import org.opends.server.core.PluginConfigManager;
import org.opends.server.loggers.debug.DebugTracer;
import org.opends.server.protocols.asn1.ASN1OctetString;
import org.opends.server.types.Attribute;
import org.opends.server.types.AttributeType;
import org.opends.server.types.AttributeValue;
import org.opends.server.types.ByteString;
import org.opends.server.types.CancelledOperationException;
import org.opends.server.types.CancelResult;
import org.opends.server.types.Control;
import org.opends.server.types.DebugLogLevel;
import org.opends.server.types.DirectoryException;
import org.opends.server.types.DN;
import org.opends.server.types.Entry;
import org.opends.server.types.LDAPException;
import org.opends.server.types.LockManager;
import org.opends.server.types.Modification;
import org.opends.server.types.ModificationType;
import org.opends.server.types.Privilege;
import org.opends.server.types.RDN;
import org.opends.server.types.ResultCode;
import org.opends.server.types.SearchFilter;
import org.opends.server.types.SearchResultEntry;
import org.opends.server.types.SynchronizationProviderResult;
import org.opends.server.types.operation.PostOperationModifyDNOperation;
import org.opends.server.types.operation.PostResponseModifyDNOperation;
import org.opends.server.types.operation.PreOperationModifyDNOperation;
import org.opends.server.types.operation.PostSynchronizationModifyDNOperation;

import static org.opends.messages.CoreMessages.*;
import static org.opends.server.loggers.ErrorLogger.*;
import static org.opends.server.loggers.debug.DebugLogger.*;
import static org.opends.server.util.ServerConstants.*;
import static org.opends.server.util.StaticUtils.*;



/**
 * This class defines an operation used to move an entry in a local backend
 * of the Directory Server.
 */
public class LocalBackendModifyDNOperation
  extends ModifyDNOperationWrapper
  implements PreOperationModifyDNOperation,
             PostOperationModifyDNOperation,
             PostResponseModifyDNOperation,
             PostSynchronizationModifyDNOperation
{
  /**
   * The tracer object for the debug logger.
   */
  private static final DebugTracer TRACER = getTracer();



  // The backend in which the operation is to be processed.
  private Backend backend;

  // Indicates whether the no-op control was included in the request.
  private boolean noOp;

  // Indicates whether to skip post-operation plugin processing.
  private boolean skipPostOperation;

  // The client connection on which this operation was requested.
  private ClientConnection clientConnection;

  // The original DN of the entry.
  DN entryDN;

  // The current entry, before it is renamed.
  private Entry currentEntry;

  // The new entry, as it will appear after it has been renamed.
  private Entry newEntry;

  // The LDAP post-read request control, if present in the request.
  private LDAPPostReadRequestControl postReadRequest;

  // The LDAP pre-read request control, if present in the request.
  private LDAPPreReadRequestControl preReadRequest;

  // The new RDN for the entry.
  private RDN newRDN;



  /**
   * Creates a new operation that may be used to move an entry in a
   * local backend of the Directory Server.
   *
   * @param operation The operation to enhance.
   */
  public LocalBackendModifyDNOperation (ModifyDNOperation operation)
  {
    super(operation);
    LocalBackendWorkflowElement.attachLocalOperation (operation, this);
  }



  /**
   * Retrieves the current entry, before it is renamed.  This will not be
   * available to pre-parse plugins or during the conflict resolution portion of
   * the synchronization processing.
   *
   * @return  The current entry, or <CODE>null</CODE> if it is not yet
   *           available.
   */
  public final Entry getOriginalEntry()
  {
    return currentEntry;
  }



  /**
   * Retrieves the new entry, as it will appear after it is renamed.  This will
   * not be  available to pre-parse plugins or during the conflict resolution
   * portion of the synchronization processing.
   *
   * @return  The updated entry, or <CODE>null</CODE> if it is not yet
   *           available.
   */
  public final Entry getUpdatedEntry()
  {
    return newEntry;
  }



  /**
   * Process this modify DN operation in a local backend.
   *
   * @param  backend  The backend in which the modify DN operation should be
   *                  processed.
   */
  void processLocalModifyDN(Backend backend)
  {
    this.backend = backend;

    clientConnection = getClientConnection();

    // Get the plugin config manager that will be used for invoking plugins.
    PluginConfigManager pluginConfigManager =
         DirectoryServer.getPluginConfigManager();
    skipPostOperation = false;

    // Check for a request to cancel this operation.
    if (cancelIfRequested())
    {
      return;
    }

    // Create a labeled block of code that we can break out of if a problem is
    // detected.
modifyDNProcessing:
    {
      // Process the entry DN, newRDN, and newSuperior elements from their raw
      // forms as provided by the client to the forms required for the rest of
      // the modify DN processing.
      entryDN = getEntryDN();

      newRDN = getNewRDN();
      if (newRDN == null)
      {
        skipPostOperation = true;
        break modifyDNProcessing;
      }

      DN newSuperior = getNewSuperior();
      if ((newSuperior == null) &&
          (getRawNewSuperior() != null))
      {
        skipPostOperation = true;
        break modifyDNProcessing;
      }

      // Construct the new DN to use for the entry.
      DN parentDN;
      if (newSuperior == null)
      {
        parentDN = entryDN.getParentDNInSuffix();
      }
      else
      {
        parentDN = newSuperior;
      }

      if ((parentDN == null) || parentDN.isNullDN())
      {
        setResultCode(ResultCode.UNWILLING_TO_PERFORM);
        appendErrorMessage(ERR_MODDN_NO_PARENT.get(String.valueOf(entryDN)));
        break modifyDNProcessing;
      }

      DN newDN = parentDN.concat(newRDN);

      // Get the backend for the current entry, and the backend for the new
      // entry.  If either is null, or if they are different, then fail.
      Backend currentBackend = backend;
      if (currentBackend == null)
      {
        setResultCode(ResultCode.NO_SUCH_OBJECT);
        appendErrorMessage(ERR_MODDN_NO_BACKEND_FOR_CURRENT_ENTRY.get(
                                String.valueOf(entryDN)));
        break modifyDNProcessing;
      }

      Backend newBackend = DirectoryServer.getBackend(newDN);
      if (newBackend == null)
      {
        setResultCode(ResultCode.NO_SUCH_OBJECT);
        appendErrorMessage(ERR_MODDN_NO_BACKEND_FOR_NEW_ENTRY.get(
                                String.valueOf(entryDN),
                                String.valueOf(newDN)));
        break modifyDNProcessing;
      }
      else if (! currentBackend.equals(newBackend))
      {
        setResultCode(ResultCode.UNWILLING_TO_PERFORM);
        appendErrorMessage(ERR_MODDN_DIFFERENT_BACKENDS.get(
                                String.valueOf(entryDN),
                                String.valueOf(newDN)));
        break modifyDNProcessing;
      }


      // Check for a request to cancel this operation.
      if (cancelIfRequested())
      {
        return;
      }


      // Acquire write locks for the current and new DN.
      Lock currentLock = null;
      for (int i=0; i < 3; i++)
      {
        currentLock = LockManager.lockWrite(entryDN);
        if (currentLock != null)
        {
          break;
        }
      }

      if (currentLock == null)
      {
        setResultCode(DirectoryServer.getServerErrorResultCode());
        appendErrorMessage(ERR_MODDN_CANNOT_LOCK_CURRENT_DN.get(
                                String.valueOf(entryDN)));
        skipPostOperation = true;
        break modifyDNProcessing;
      }

      Lock newLock = null;
      try
      {
        for (int i=0; i < 3; i++)
        {
          newLock = LockManager.lockWrite(newDN);
          if (newLock != null)
          {
            break;
          }
        }
      }
      catch (Exception e)
      {
        if (debugEnabled())
        {
          TRACER.debugCaught(DebugLogLevel.ERROR, e);
        }

        LockManager.unlock(entryDN, currentLock);

        if (newLock != null)
        {
          LockManager.unlock(newDN, newLock);
        }

        setResultCode(DirectoryServer.getServerErrorResultCode());
        appendErrorMessage(ERR_MODDN_EXCEPTION_LOCKING_NEW_DN.get(
                                String.valueOf(entryDN), String.valueOf(newDN),
                                getExceptionMessage(e)));

        skipPostOperation = true;
        break modifyDNProcessing;
      }

      if (newLock == null)
      {
        LockManager.unlock(entryDN, currentLock);

        setResultCode(DirectoryServer.getServerErrorResultCode());
        appendErrorMessage(ERR_MODDN_CANNOT_LOCK_NEW_DN.get(
                                String.valueOf(entryDN),
                                String.valueOf(newDN)));
        skipPostOperation = true;
        break modifyDNProcessing;
      }

      try
      {
        // Check for a request to cancel this operation.
        if (cancelIfRequested())
        {
          return;
        }


        // Get the current entry from the appropriate backend.  If it doesn't
        // exist, then fail.
        try
        {
          currentEntry = currentBackend.getEntry(entryDN);
        }
        catch (DirectoryException de)
        {
          if (debugEnabled())
          {
            TRACER.debugCaught(DebugLogLevel.ERROR, de);
          }

          setResponseData(de);
          break modifyDNProcessing;
        }

        if (getOriginalEntry() == null)
        {
          // See if one of the entry's ancestors exists.
          parentDN = entryDN.getParentDNInSuffix();
          while (parentDN != null)
          {
            try
            {
              if (DirectoryServer.entryExists(parentDN))
              {
                setMatchedDN(parentDN);
                break;
              }
            }
            catch (Exception e)
            {
              if (debugEnabled())
              {
                TRACER.debugCaught(DebugLogLevel.ERROR, e);
              }
              break;
            }

            parentDN = parentDN.getParentDNInSuffix();
          }

          setResultCode(ResultCode.NO_SUCH_OBJECT);
          appendErrorMessage(ERR_MODDN_NO_CURRENT_ENTRY.get(
                                  String.valueOf(entryDN)));
          break modifyDNProcessing;
        }


        // Invoke any conflict resolution processing that might be needed by the
        // synchronization provider.
        for (SynchronizationProvider provider :
             DirectoryServer.getSynchronizationProviders())
        {
          try
          {
            SynchronizationProviderResult result =
                 provider.handleConflictResolution(this);
            if (! result.continueOperationProcessing())
            {
              break modifyDNProcessing;
            }
          }
          catch (DirectoryException de)
          {
            if (debugEnabled())
            {
              TRACER.debugCaught(DebugLogLevel.ERROR, de);
            }

            logError(ERR_MODDN_SYNCH_CONFLICT_RESOLUTION_FAILED.get(
                          getConnectionID(), getOperationID(),
                          getExceptionMessage(de)));

            setResponseData(de);
            break modifyDNProcessing;
          }
        }


        // Check to see if there are any controls in the request.  If so, then
        // see if there is any special processing required.
        try
        {
          handleRequestControls();
        }
        catch (DirectoryException de)
        {
          if (debugEnabled())
          {
            TRACER.debugCaught(DebugLogLevel.ERROR, de);
          }

          setResponseData(de);
          break modifyDNProcessing;
        }


        // Check to see if the client has permission to perform the
        // modify DN.

        // FIXME: for now assume that this will check all permission
        // pertinent to the operation. This includes proxy authorization
        // and any other controls specified.

        // FIXME: earlier checks to see if the entry or new superior
        // already exists may have already exposed sensitive information
        // to the client.
        if (! AccessControlConfigManager.getInstance().
                   getAccessControlHandler().isAllowed(this))
        {
          setResultCode(ResultCode.INSUFFICIENT_ACCESS_RIGHTS);
          appendErrorMessage(ERR_MODDN_AUTHZ_INSUFFICIENT_ACCESS_RIGHTS.get(
                                  String.valueOf(entryDN)));
          skipPostOperation = true;
          break modifyDNProcessing;
        }

        // Duplicate the entry and set its new DN.  Also, create an empty list
        // to hold the attribute-level modifications.
        newEntry = currentEntry.duplicate(false);
        newEntry.setDN(newDN);

        // init the modifications
        addModification(null);
        List<Modification> modifications = this.getModifications();



        // Apply any changes to the entry based on the change in its RDN.  Also,
        // perform schema checking on the updated entry.
        try
        {
          applyRDNChanges(modifications);
        }
        catch (DirectoryException de)
        {
          if (debugEnabled())
          {
            TRACER.debugCaught(DebugLogLevel.ERROR, de);
          }

          setResponseData(de);
          break modifyDNProcessing;
        }


        // Check for a request to cancel this operation.
        if (cancelIfRequested())
        {
          return;
        }


        // Get a count of the current number of modifications.  The
        // pre-operation plugins may alter this list, and we need to be able to
        // identify which changes were made after they're done.
        int modCount = modifications.size();


        // If the operation is not a synchronization operation,
        // Invoke the pre-operation modify DN plugins.
        if (! isSynchronizationOperation())
        {
          PreOperationPluginResult preOpResult =
            pluginConfigManager.invokePreOperationModifyDNPlugins(this);
          if (preOpResult.connectionTerminated())
          {
            // There's no point in continuing with anything.  Log the request
            // and result and return.
            setResultCode(ResultCode.CANCELED);
            appendErrorMessage(ERR_CANCELED_BY_PREOP_DISCONNECT.get());
            return;
          }
          else if (preOpResult.sendResponseImmediately())
          {
            skipPostOperation = true;
            break modifyDNProcessing;
          }
          else if (preOpResult.skipCoreProcessing())
          {
            skipPostOperation = false;
            break modifyDNProcessing;
          }
        }


        // Check to see if any of the pre-operation plugins made any changes to
        // the entry.  If so, then apply them.
        if (modifications.size() > modCount)
        {
          try
          {
            applyPreOpModifications(modifications, modCount);
          }
          catch (DirectoryException de)
          {
            if (debugEnabled())
            {
              TRACER.debugCaught(DebugLogLevel.ERROR, de);
            }

            setResponseData(de);
            break modifyDNProcessing;
          }
        }


        // Check for a request to cancel this operation.
        if (cancelIfRequested())
        {
          return;
        }


        // Actually perform the modify DN operation.
        // This should include taking
        // care of any synchronization that might be needed.
        try
        {
          // If it is not a private backend, then check to see if the server or
          // backend is operating in read-only mode.
          if (! currentBackend.isPrivateBackend())
          {
            switch (DirectoryServer.getWritabilityMode())
            {
              case DISABLED:
                setResultCode(ResultCode.UNWILLING_TO_PERFORM);
                appendErrorMessage(ERR_MODDN_SERVER_READONLY.get(
                                        String.valueOf(entryDN)));
                break modifyDNProcessing;

              case INTERNAL_ONLY:
                if (! (isInternalOperation() || isSynchronizationOperation()))
                {
                  setResultCode(ResultCode.UNWILLING_TO_PERFORM);
                  appendErrorMessage(ERR_MODDN_SERVER_READONLY.get(
                                          String.valueOf(entryDN)));
                  break modifyDNProcessing;
                }
            }

            switch (currentBackend.getWritabilityMode())
            {
              case DISABLED:
                setResultCode(ResultCode.UNWILLING_TO_PERFORM);
                appendErrorMessage(ERR_MODDN_BACKEND_READONLY.get(
                                        String.valueOf(entryDN)));
                break modifyDNProcessing;

              case INTERNAL_ONLY:
                if (! (isInternalOperation() || isSynchronizationOperation()))
                {
                  setResultCode(ResultCode.UNWILLING_TO_PERFORM);
                  appendErrorMessage(ERR_MODDN_BACKEND_READONLY.get(
                                          String.valueOf(entryDN)));
                  break modifyDNProcessing;
                }
            }
          }


          if (noOp)
          {
            appendErrorMessage(INFO_MODDN_NOOP.get());
            setResultCode(ResultCode.NO_OPERATION);
          }
          else
          {
            for (SynchronizationProvider provider :
                 DirectoryServer.getSynchronizationProviders())
            {
              try
              {
                SynchronizationProviderResult result =
                     provider.doPreOperation(this);
                if (! result.continueOperationProcessing())
                {
                  break modifyDNProcessing;
                }
              }
              catch (DirectoryException de)
              {
                if (debugEnabled())
                {
                  TRACER.debugCaught(DebugLogLevel.ERROR, de);
                }

                logError(ERR_MODDN_SYNCH_PREOP_FAILED.get(getConnectionID(),
                              getOperationID(), getExceptionMessage(de)));
                setResponseData(de);
                break modifyDNProcessing;
              }
            }

            currentBackend.renameEntry(entryDN, newEntry, this);
          }


          // Attach the pre-read and/or post-read controls to the response if
          // appropriate.
          processReadEntryControls();


          if (! noOp)
          {
            setResultCode(ResultCode.SUCCESS);
          }
        }
        catch (DirectoryException de)
        {
          if (debugEnabled())
          {
            TRACER.debugCaught(DebugLogLevel.ERROR, de);
          }

          setResponseData(de);
          break modifyDNProcessing;
        }
        catch (CancelledOperationException coe)
        {
          if (debugEnabled())
          {
            TRACER.debugCaught(DebugLogLevel.ERROR, coe);
          }

          CancelResult cancelResult = coe.getCancelResult();

          setCancelResult(cancelResult);
          setResultCode(cancelResult.getResultCode());

          Message message = coe.getMessageObject();
          if ((message != null) && (message.length() > 0))
          {
            appendErrorMessage(message);
          }

          break modifyDNProcessing;
        }
      }
      finally
      {
        LockManager.unlock(entryDN, currentLock);
        LockManager.unlock(newDN, newLock);

        for (SynchronizationProvider provider :
             DirectoryServer.getSynchronizationProviders())
        {
          try
          {
            provider.doPostOperation(this);
          }
          catch (DirectoryException de)
          {
            if (debugEnabled())
            {
              TRACER.debugCaught(DebugLogLevel.ERROR, de);
            }

            logError(ERR_MODDN_SYNCH_POSTOP_FAILED.get(getConnectionID(),
                          getOperationID(), getExceptionMessage(de)));
            setResponseData(de);
            break;
          }
        }
      }
    }


    // Indicate that it is now too late to attempt to cancel the operation.
    setCancelResult(CancelResult.TOO_LATE);


    // Invoke the post-operation or post-synchronization modify DN plugins.
    if (isSynchronizationOperation())
    {
      if (getResultCode() == ResultCode.SUCCESS)
      {
        pluginConfigManager.invokePostSynchronizationModifyDNPlugins(this);
      }
    }
    else if (! skipPostOperation)
    {
      PostOperationPluginResult postOperationResult =
           pluginConfigManager.invokePostOperationModifyDNPlugins(this);
      if (postOperationResult.connectionTerminated())
      {
        setResultCode(ResultCode.CANCELED);
        appendErrorMessage(ERR_CANCELED_BY_POSTOP_DISCONNECT.get());
        return;
      }
    }


    // Notify any change notification listeners that might be registered with
    // the server.
    if (getResultCode() == ResultCode.SUCCESS)
    {
      for (ChangeNotificationListener changeListener :
           DirectoryServer.getChangeNotificationListeners())
      {
        try
        {
          changeListener.handleModifyDNOperation(this, currentEntry, newEntry);
        }
        catch (Exception e)
        {
          if (debugEnabled())
          {
            TRACER.debugCaught(DebugLogLevel.ERROR, e);
          }

          Message message = ERR_MODDN_ERROR_NOTIFYING_CHANGE_LISTENER.get(
              getExceptionMessage(e));
          logError(message);
        }
      }
    }
  }



  /**
   * Checks to determine whether there has been a request to cancel this
   * operation.  If so, then set the cancel result and processing stop time.
   *
   * @return  {@code true} if there was a cancel request, or {@code false} if
   *          not.
   */
  private boolean cancelIfRequested()
  {
    if (getCancelRequest() == null)
    {
      return false;
    }

    indicateCancelled(getCancelRequest());
    setProcessingStopTime();
    return true;
  }



  /**
   * Processes the set of controls included in the request.
   *
   * @throws  DirectoryException  If a problem occurs that should cause the
   *                              modify DN operation to fail.
   */
  private void handleRequestControls()
          throws DirectoryException
  {
    List<Control> requestControls = getRequestControls();
    if ((requestControls != null) && (! requestControls.isEmpty()))
    {
      for (int i=0; i < requestControls.size(); i++)
      {
        Control c   = requestControls.get(i);
        String  oid = c.getOID();

        if (! AccessControlConfigManager.getInstance().
                   getAccessControlHandler().isAllowed(entryDN,  this, c))
        {
          skipPostOperation = true;
          throw new DirectoryException(ResultCode.INSUFFICIENT_ACCESS_RIGHTS,
                         ERR_CONTROL_INSUFFICIENT_ACCESS_RIGHTS.get(oid));
        }

        if (oid.equals(OID_LDAP_ASSERTION))
        {
          LDAPAssertionRequestControl assertControl;
          if (c instanceof LDAPAssertionRequestControl)
          {
            assertControl = (LDAPAssertionRequestControl) c;
          }
          else
          {
            try
            {
              assertControl = LDAPAssertionRequestControl.decodeControl(c);
              requestControls.set(i, assertControl);
            }
            catch (LDAPException le)
            {
              if (debugEnabled())
              {
                TRACER.debugCaught(DebugLogLevel.ERROR, le);
              }

              throw new DirectoryException(
                             ResultCode.valueOf(le.getResultCode()),
                             le.getMessageObject());
            }
          }

          try
          {
            // FIXME -- We need to determine whether the current user has
            //          permission to make this determination.
            SearchFilter filter = assertControl.getSearchFilter();
            if (! filter.matchesEntry(currentEntry))
            {
              throw new DirectoryException(ResultCode.ASSERTION_FAILED,
                                           ERR_MODDN_ASSERTION_FAILED.get(
                                                String.valueOf(entryDN)));
            }
          }
          catch (DirectoryException de)
          {
            if (de.getResultCode() == ResultCode.ASSERTION_FAILED)
            {
              throw de;
            }

            if (debugEnabled())
            {
              TRACER.debugCaught(DebugLogLevel.ERROR, de);
            }

            throw new DirectoryException(ResultCode.PROTOCOL_ERROR,
                           ERR_MODDN_CANNOT_PROCESS_ASSERTION_FILTER.get(
                                String.valueOf(entryDN),
                                de.getMessageObject()));
          }
        }
        else if (oid.equals(OID_LDAP_NOOP_OPENLDAP_ASSIGNED))
        {
          noOp = true;
        }
        else if (oid.equals(OID_LDAP_READENTRY_PREREAD))
        {
          if (c instanceof LDAPPreReadRequestControl)
          {
            preReadRequest = (LDAPPreReadRequestControl) c;
          }
          else
          {
            try
            {
              preReadRequest = LDAPPreReadRequestControl.decodeControl(c);
              requestControls.set(i, preReadRequest);
            }
            catch (LDAPException le)
            {
              if (debugEnabled())
              {
                TRACER.debugCaught(DebugLogLevel.ERROR, le);
              }

              throw new DirectoryException(
                             ResultCode.valueOf(le.getResultCode()),
                             le.getMessageObject());
            }
          }
        }
        else if (oid.equals(OID_LDAP_READENTRY_POSTREAD))
        {
          if (c instanceof LDAPPostReadRequestControl)
          {
            postReadRequest = (LDAPPostReadRequestControl) c;
          }
          else
          {
            try
            {
              postReadRequest = LDAPPostReadRequestControl.decodeControl(c);
              requestControls.set(i, postReadRequest);
            }
            catch (LDAPException le)
            {
              if (debugEnabled())
              {
                TRACER.debugCaught(DebugLogLevel.ERROR, le);
              }

              throw new DirectoryException(
                             ResultCode.valueOf(le.getResultCode()),
                             le.getMessageObject());
            }
          }
        }
        else if (oid.equals(OID_PROXIED_AUTH_V1))
        {
          // The requester must have the PROXIED_AUTH privilige in order to
          // be able to use this control.
          if (! clientConnection.hasPrivilege(Privilege.PROXIED_AUTH, this))
          {
            throw new DirectoryException(ResultCode.AUTHORIZATION_DENIED,
                           ERR_PROXYAUTH_INSUFFICIENT_PRIVILEGES.get());
          }


          ProxiedAuthV1Control proxyControl;
          if (c instanceof ProxiedAuthV1Control)
          {
            proxyControl = (ProxiedAuthV1Control) c;
          }
          else
          {
            try
            {
              proxyControl = ProxiedAuthV1Control.decodeControl(c);
            }
            catch (LDAPException le)
            {
              if (debugEnabled())
              {
                TRACER.debugCaught(DebugLogLevel.ERROR, le);
              }

              throw new DirectoryException(
                             ResultCode.valueOf(le.getResultCode()),
                             le.getMessageObject());
            }
          }


          Entry authorizationEntry = proxyControl.getAuthorizationEntry();
          setAuthorizationEntry(authorizationEntry);
          if (authorizationEntry == null)
          {
            setProxiedAuthorizationDN(DN.nullDN());
          }
          else
          {
            setProxiedAuthorizationDN(authorizationEntry.getDN());
          }
        }
        else if (oid.equals(OID_PROXIED_AUTH_V2))
        {
          // The requester must have the PROXIED_AUTH privilige in order to
          // be able to use this control.
          if (! clientConnection.hasPrivilege(Privilege.PROXIED_AUTH, this))
          {
            throw new DirectoryException(ResultCode.AUTHORIZATION_DENIED,
                           ERR_PROXYAUTH_INSUFFICIENT_PRIVILEGES.get());
          }


          ProxiedAuthV2Control proxyControl;
          if (c instanceof ProxiedAuthV2Control)
          {
            proxyControl = (ProxiedAuthV2Control) c;
          }
          else
          {
            try
            {
              proxyControl = ProxiedAuthV2Control.decodeControl(c);
            }
            catch (LDAPException le)
            {
              if (debugEnabled())
              {
                TRACER.debugCaught(DebugLogLevel.ERROR, le);
              }

              throw new DirectoryException(
                             ResultCode.valueOf(le.getResultCode()),
                             le.getMessageObject());
            }
          }


          Entry authorizationEntry = proxyControl.getAuthorizationEntry();
          setAuthorizationEntry(authorizationEntry);
          if (authorizationEntry == null)
          {
            setProxiedAuthorizationDN(DN.nullDN());
          }
          else
          {
            setProxiedAuthorizationDN(authorizationEntry.getDN());
          }
        }

        // NYI -- Add support for additional controls.

        else if (c.isCritical())
        {
          if ((backend == null) || (! backend.supportsControl(oid)))
          {
            throw new DirectoryException(
                           ResultCode.UNAVAILABLE_CRITICAL_EXTENSION,
                           ERR_MODDN_UNSUPPORTED_CRITICAL_CONTROL.get(
                                String.valueOf(entryDN), oid));
          }
        }
      }
    }
  }



  /**
   * Updates the entry so that its attributes are changed to reflect the changes
   * to the RDN.  This also performs schema checking on the updated entry.
   *
   * @param  modifications  A list to hold the modifications made to the entry.
   *
   * @throws  DirectoryException  If a problem occurs that should cause the
   *                              modify DN operation to fail.
   */
  private void applyRDNChanges(List<Modification> modifications)
          throws DirectoryException
  {
    // If we should delete the old RDN values from the entry, then do so.
    if (deleteOldRDN())
    {
      RDN currentRDN = entryDN.getRDN();
      int numValues  = currentRDN.getNumValues();
      for (int i=0; i < numValues; i++)
      {
        LinkedHashSet<AttributeValue> valueSet =
             new LinkedHashSet<AttributeValue>(1);
        valueSet.add(currentRDN.getAttributeValue(i));

        Attribute a = new Attribute(currentRDN.getAttributeType(i),
                                    currentRDN.getAttributeName(i), valueSet);

        // If the associated attribute type is marked NO-USER-MODIFICATION, then
        // refuse the update.
        if (a.getAttributeType().isNoUserModification())
        {
          if (! (isInternalOperation() || isSynchronizationOperation()))
          {
            throw new DirectoryException(ResultCode.UNWILLING_TO_PERFORM,
                           ERR_MODDN_OLD_RDN_ATTR_IS_NO_USER_MOD.get(
                                String.valueOf(entryDN), a.getName()));
          }
        }

        LinkedList<AttributeValue> missingValues =
             new LinkedList<AttributeValue>();
        newEntry.removeAttribute(a, missingValues);

        if (missingValues.isEmpty())
        {
          modifications.add(new Modification(ModificationType.DELETE, a));
        }
      }
    }


    // Add the new RDN values to the entry.
    int newRDNValues = newRDN.getNumValues();
    for (int i=0; i < newRDNValues; i++)
    {
      LinkedHashSet<AttributeValue> valueSet =
           new LinkedHashSet<AttributeValue>(1);
      valueSet.add(newRDN.getAttributeValue(i));

      Attribute a = new Attribute(newRDN.getAttributeType(i),
                                  newRDN.getAttributeName(i), valueSet);

      LinkedList<AttributeValue> duplicateValues =
           new LinkedList<AttributeValue>();
      newEntry.addAttribute(a, duplicateValues);

      if (duplicateValues.isEmpty())
      {
        // If the associated attribute type is marked NO-USER-MODIFICATION, then
        // refuse the update.
        if (a.getAttributeType().isNoUserModification())
        {
          if (! (isInternalOperation() || isSynchronizationOperation()))
          {
            throw new DirectoryException(ResultCode.UNWILLING_TO_PERFORM,
                           ERR_MODDN_NEW_RDN_ATTR_IS_NO_USER_MOD.get(
                                String.valueOf(entryDN), a.getName()));
          }
        }
        else
        {
          modifications.add(new Modification(ModificationType.ADD, a));
        }
      }
    }

    // If the server is configured to check the schema and the operation is not
    // a synchronization operation, make sure that the resulting entry is valid
    // as per the server schema.
    if ((DirectoryServer.checkSchema()) && (! isSynchronizationOperation()))
    {
      MessageBuilder invalidReason = new MessageBuilder();
      if (! newEntry.conformsToSchema(null, false, true, true,
                                      invalidReason))
      {
        throw new DirectoryException(ResultCode.OBJECTCLASS_VIOLATION,
                                     ERR_MODDN_VIOLATES_SCHEMA.get(
                                          String.valueOf(entryDN),
                                          String.valueOf(invalidReason)));
      }

      for (int i=0; i < newRDNValues; i++)
      {
        AttributeType at = newRDN.getAttributeType(i);
        if (at.isObsolete())
        {
          throw new DirectoryException(ResultCode.UNWILLING_TO_PERFORM,
                                       ERR_MODDN_NEWRDN_ATTR_IS_OBSOLETE.get(
                                            String.valueOf(entryDN),
                                            at.getNameOrOID()));
        }
      }
    }
  }



  /**
   * Applies any modifications performed during pre-operation plugin processing.
   * This also performs schema checking for the updated entry.
   *
   * @param  modifications  A list containing the modifications made to the
   *                        entry.
   * @param  startPos       The position in the list at which the pre-operation
   *                        modifications start.
   *
   * @throws  DirectoryException  If a problem occurs that should cause the
   *                              modify DN operation to fail.
   */
  private void applyPreOpModifications(List<Modification> modifications,
                                       int startPos)
          throws DirectoryException
  {
    for (int i=startPos; i < modifications.size(); i++)
    {
      Modification m = modifications.get(i);
      Attribute    a = m.getAttribute();

      switch (m.getModificationType())
      {
        case ADD:
          LinkedList<AttributeValue> duplicateValues =
               new LinkedList<AttributeValue>();
          newEntry.addAttribute(a, duplicateValues);
          break;

        case DELETE:
          LinkedList<AttributeValue> missingValues =
               new LinkedList<AttributeValue>();
          newEntry.removeAttribute(a, missingValues);
          break;

        case REPLACE:
          duplicateValues = new LinkedList<AttributeValue>();
          newEntry.removeAttribute(a.getAttributeType(), a.getOptions());
          newEntry.addAttribute(a, duplicateValues);
          break;

        case INCREMENT:
          List<Attribute> attrList =
               newEntry.getAttribute(a.getAttributeType(),
                                     a.getOptions());
          if ((attrList == null) || attrList.isEmpty())
          {
            throw new DirectoryException(ResultCode.NO_SUCH_ATTRIBUTE,
                                         ERR_MODDN_PREOP_INCREMENT_NO_ATTR.get(
                                              String.valueOf(entryDN),
                                              a.getName()));
          }
          else if (attrList.size() > 1)
          {
            throw new DirectoryException(ResultCode.CONSTRAINT_VIOLATION,
                           ERR_MODDN_PREOP_INCREMENT_MULTIPLE_VALUES.get(
                                String.valueOf(entryDN), a.getName()));
          }

          LinkedHashSet<AttributeValue> values =
               attrList.get(0).getValues();
          if ((values == null) || values.isEmpty())
          {
            throw new DirectoryException(ResultCode.NO_SUCH_ATTRIBUTE,
                                         ERR_MODDN_PREOP_INCREMENT_NO_ATTR.get(
                                              String.valueOf(entryDN),
                                              a.getName()));
          }
          else if (values.size() > 1)
          {
            throw new DirectoryException(ResultCode.CONSTRAINT_VIOLATION,
                           ERR_MODDN_PREOP_INCREMENT_MULTIPLE_VALUES.get(
                                String.valueOf(entryDN), a.getName()));
          }

          long currentLongValue;
          try
          {
            AttributeValue v = values.iterator().next();
            currentLongValue = Long.parseLong(v.getStringValue());
          }
          catch (Exception e)
          {
            if (debugEnabled())
            {
              TRACER.debugCaught(DebugLogLevel.ERROR, e);
            }

            throw new DirectoryException(ResultCode.CONSTRAINT_VIOLATION,
                           ERR_MODDN_PREOP_INCREMENT_VALUE_NOT_INTEGER.get(
                                String.valueOf(entryDN), a.getName()));
          }

          LinkedHashSet<AttributeValue> newValues = a.getValues();
          if ((newValues == null) || newValues.isEmpty())
          {
            throw new DirectoryException(ResultCode.CONSTRAINT_VIOLATION,
                           ERR_MODDN_PREOP_INCREMENT_NO_AMOUNT.get(
                                String.valueOf(entryDN), a.getName()));
          }
          else if (newValues.size() > 1)
          {
            throw new DirectoryException(ResultCode.CONSTRAINT_VIOLATION,
                           ERR_MODDN_PREOP_INCREMENT_MULTIPLE_AMOUNTS.get(
                                String.valueOf(entryDN), a.getName()));
          }

          long incrementAmount;
          try
          {
            AttributeValue v = values.iterator().next();
            incrementAmount = Long.parseLong(v.getStringValue());
          }
          catch (Exception e)
          {
            if (debugEnabled())
            {
              TRACER.debugCaught(DebugLogLevel.ERROR, e);
            }

            throw new DirectoryException(ResultCode.CONSTRAINT_VIOLATION,
                           ERR_MODDN_PREOP_INCREMENT_AMOUNT_NOT_INTEGER.get(
                                String.valueOf(entryDN), a.getName()));
          }

          long newLongValue = currentLongValue + incrementAmount;
          ByteString newValueOS =
               new ASN1OctetString(String.valueOf(newLongValue));

          newValues = new LinkedHashSet<AttributeValue>(1);
          newValues.add(new AttributeValue(a.getAttributeType(),
                                           newValueOS));

          List<Attribute> newAttrList = new ArrayList<Attribute>(1);
          newAttrList.add(new Attribute(a.getAttributeType(),
                                        a.getName(), newValues));
          newEntry.putAttribute(a.getAttributeType(), newAttrList);

          break;
      }
    }


    // Make sure that the updated entry still conforms to the server
    // schema.
    if (DirectoryServer.checkSchema())
    {
      MessageBuilder invalidReason = new MessageBuilder();
      if (! newEntry.conformsToSchema(null, false, true, true,
                                      invalidReason))
      {
        throw new DirectoryException(ResultCode.OBJECTCLASS_VIOLATION,
                                     ERR_MODDN_PREOP_VIOLATES_SCHEMA.get(
                                          String.valueOf(entryDN),
                                          String.valueOf(invalidReason)));
      }
    }
  }



  /**
   * Performs any necessary processing to create the pre-read and/or post-read
   * response controls and attach them to the response.
   */
  private void processReadEntryControls()
  {
    if (preReadRequest != null)
    {
      Entry entry = currentEntry.duplicate(true);

      if (! preReadRequest.allowsAttribute(
                 DirectoryServer.getObjectClassAttributeType()))
      {
        entry.removeAttribute(
             DirectoryServer.getObjectClassAttributeType());
      }

      if (! preReadRequest.returnAllUserAttributes())
      {
        Iterator<AttributeType> iterator =
             entry.getUserAttributes().keySet().iterator();
        while (iterator.hasNext())
        {
          AttributeType attrType = iterator.next();
          if (! preReadRequest.allowsAttribute(attrType))
          {
            iterator.remove();
          }
        }
      }

      if (! preReadRequest.returnAllOperationalAttributes())
      {
        Iterator<AttributeType> iterator =
             entry.getOperationalAttributes().keySet().iterator();
        while (iterator.hasNext())
        {
          AttributeType attrType = iterator.next();
          if (! preReadRequest.allowsAttribute(attrType))
          {
            iterator.remove();
          }
        }
      }

      // FIXME -- Check access controls on the entry to see if it should
      //          be returned or if any attributes need to be stripped
      //          out..
      SearchResultEntry searchEntry = new SearchResultEntry(entry);
      LDAPPreReadResponseControl responseControl =
           new LDAPPreReadResponseControl(preReadRequest.getOID(),
                                          preReadRequest.isCritical(),
                                          searchEntry);

      addResponseControl(responseControl);
    }

    if (postReadRequest != null)
    {
      Entry entry = newEntry.duplicate(true);

      if (! postReadRequest.allowsAttribute(
                 DirectoryServer.getObjectClassAttributeType()))
      {
        entry.removeAttribute(
             DirectoryServer.getObjectClassAttributeType());
      }

      if (! postReadRequest.returnAllUserAttributes())
      {
        Iterator<AttributeType> iterator =
             entry.getUserAttributes().keySet().iterator();
        while (iterator.hasNext())
        {
          AttributeType attrType = iterator.next();
          if (! postReadRequest.allowsAttribute(attrType))
          {
            iterator.remove();
          }
        }
      }

      if (! postReadRequest.returnAllOperationalAttributes())
      {
        Iterator<AttributeType> iterator =
             entry.getOperationalAttributes().keySet().iterator();
        while (iterator.hasNext())
        {
          AttributeType attrType = iterator.next();
          if (! postReadRequest.allowsAttribute(attrType))
          {
            iterator.remove();
          }
        }
      }

      // FIXME -- Check access controls on the entry to see if it should
      //          be returned or if any attributes need to be stripped
      //          out..
      SearchResultEntry searchEntry = new SearchResultEntry(entry);
      LDAPPostReadResponseControl responseControl =
           new LDAPPostReadResponseControl(postReadRequest.getOID(),
                                           postReadRequest.isCritical(),
                                           searchEntry);

      addResponseControl(responseControl);
    }
  }
}

