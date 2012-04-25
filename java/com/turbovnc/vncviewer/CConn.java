/* Copyright (C) 2002-2005 RealVNC Ltd.  All Rights Reserved.
 * Copyright 2009-2011 Pierre Ossman <ossman@cendio.se> for Cendio AB
 * Copyright (C) 2011-2012 D. R. Commander.  All Rights Reserved.
 * Copyright (C) 2011 Brian P Hinz
 * 
 * This is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 * 
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this software; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301,
 * USA.
 */

//
// CConn
//
// Methods on CConn are called from both the GUI thread and the thread which
// processes incoming RFB messages ("the RFB thread").  This means we need to
// be careful with synchronization here.
//
// Any access to writer() must not only be synchronized, but we must also make
// sure that the connection is in RFBSTATE_NORMAL.  We are guaranteed this for
// any code called after serverInit() has been called.  Since the DesktopWindow
// isn't created until then, any methods called only from DesktopWindow can
// assume that we are in RFBSTATE_NORMAL.

package com.turbovnc.vncviewer;

import java.awt.event.*;
import java.awt.Dimension;
import java.awt.Event;

import java.io.IOException;
import java.io.InputStream;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import javax.swing.*;
import javax.swing.ImageIcon;
import java.net.InetSocketAddress;
import java.net.URL;
import java.net.SocketException;
import java.util.*;

import com.turbovnc.rdr.*;
import com.turbovnc.rfb.*;
import com.turbovnc.rfb.Exception;
import com.turbovnc.network.Socket;
import com.turbovnc.network.TcpSocket;

public class CConn extends CConnection
  implements UserPasswdGetter, UserMsgBox, OptionsDialogCallback, FdInStreamBlockCallback
{

  public final PixelFormat getPreferredPF() { return fullColourPF; }
  static final PixelFormat verylowColourPF = 
    new PixelFormat(8, 3, false, true, 1, 1, 1, 2, 1, 0);
  static final PixelFormat lowColourPF = 
    new PixelFormat(8, 6, false, true, 3, 3, 3, 4, 2, 0);
  static final PixelFormat mediumColourPF = 
    new PixelFormat(8, 8, false, false, 7, 7, 3, 0, 3, 6);

  ////////////////////////////////////////////////////////////////////
  // The following methods are all called from the RFB thread

  public CConn(VncViewer viewer_, Socket sock_, 
               String vncServerName) 
  {
    serverHost = null; serverPort = 0; sock = sock_; viewer = viewer_; 
    pendingPFChange = false;
    currentEncoding = Encodings.encodingTight; lastServerEncoding = -1;
    fullColour = true;
    lowColourLevel = -1;
    switch (viewer.colors.getValue()) {
      case 8:
        lowColourLevel = 0;
        break;
      case 64:
        lowColourLevel = 1;
        break;
      case 256:
        lowColourLevel = 2;
        break;
    }
    if (lowColourLevel >= 0)
      fullColour = false;
    formatChange = false; encodingChange = false;
    fullScreen = viewer.fullScreen.getValue();
    menuKey = Keysyms.F8;
    options = new OptionsDialog(this);
    options.initDialog();
    clipboardDialog = new ClipboardDialog(this);
    firstUpdate = true; pendingUpdate = false; continuousUpdates = false; 
    forceNonincremental = true; supportsSyncFence = false;
    

    setShared(viewer.shared.getValue());
    upg = this;
    msg = this;

    String encStr = viewer.preferredEncoding.getValue();
    int encNum = Encodings.encodingNum(encStr);
    if (encNum != -1) {
      currentEncoding = encNum;
    }
    cp.supportsDesktopResize = true;
    cp.supportsExtendedDesktopSize = true;
    cp.supportsSetDesktopSize = true;
    cp.supportsClientRedirect = true;
    cp.supportsDesktopRename = true;
    cp.supportsLocalCursor = viewer.useLocalCursor.getValue();
    cp.compressLevel = viewer.compressLevel.getValue();
    cp.allowJpeg = viewer.allowJpeg.getValue();
    cp.quality = viewer.quality.getValue();
    switch (viewer.subsampling.getValue().toUpperCase().charAt(0)) {
      case '1':
      case 'N':
        cp.subsampling = ConnParams.SUBSAMP_NONE;
        break;
      case '2':
        cp.subsampling = ConnParams.SUBSAMP_422;
        break;
      case '4':
        cp.subsampling = ConnParams.SUBSAMP_420;
        break;
      case 'G':
        cp.subsampling = ConnParams.SUBSAMP_GRAY;
        break;
    }
    initMenu();

    if (sock != null) {
      String name = sock.getPeerEndpoint();
      vlog.info("Accepted connection from "+name);
    } else {
      if (vncServerName != null &&
          !viewer.alwaysShowConnectionDialog.getValue()) {
        serverHost = Hostname.getHost(vncServerName);
        serverPort = Hostname.getPort(vncServerName);
      } else {
        ServerDialog dlg = new ServerDialog(options, vncServerName, this);
        if (!dlg.showDialog() || dlg.server.getSelectedItem().equals("")) {
          System.exit(1);
        }
        vncServerName = (String)dlg.server.getSelectedItem();
        serverHost = Hostname.getHost(vncServerName);
        serverPort = Hostname.getPort(vncServerName);
      }

      try {
        sock = new TcpSocket(serverHost, serverPort);
      } catch (java.lang.Exception e) {
        throw new Exception(e.toString());
      }
      vlog.info("connected to host "+serverHost+" port "+serverPort);
    }

    sock.inStream().setBlockCallback(this);
    setServerName(serverHost);
    setStreams(sock.inStream(), sock.outStream());
    initialiseProtocol();
  }

  public void refreshFramebuffer()
  {
    forceNonincremental = true;

    // Without fences, we cannot safely trigger an update request directly
    // but must wait for the next update to arrive.
    if (supportsSyncFence)
      requestNewUpdate();
  }
  

  public boolean showMsgBox(int flags, String title, String text)
  {
    //StringBuffer titleText = new StringBuffer("VNC Viewer: "+title);
    return true;
  }

  // deleteWindow() is called when the user closes the desktop or menu windows.

  void deleteWindow() {
    if (viewport != null)
      viewport.dispose();
    viewport = null;
    System.exit(1);
  } 

  // blockCallback() is called when reading from the socket would block.
  public void blockCallback() {
    try {
      synchronized(this) {
        wait(1);
      }
    } catch (java.lang.InterruptedException e) {
      throw new Exception(e.toString());
    }
  }  

  // getUserPasswd() is called by the CSecurity object when it needs us to read
  // a password from the user.

  public final boolean getUserPasswd(StringBuffer user, StringBuffer passwd) {
    String title = ((user==null? "Standard VNC Authentication":"Unix Login Authentication")
                    + " [" + csecurity.description() + "]");
    String passwordFileStr = viewer.passwordFile.getValue();
    PasswdDialog dlg;    

    if (user == null && passwordFileStr != null) {
      InputStream fp = null;
      try {
        fp = new FileInputStream(passwordFileStr);
      } catch(FileNotFoundException e) {
        throw new Exception("Opening password file failed");
      }
      byte[] obfPwd = new byte[256];
      try {
        fp.read(obfPwd);
        fp.close();
      } catch(IOException e) {
        throw new Exception("Failed to read VncPasswd file");
      }
      String PlainPasswd =
        VncAuth.unobfuscatePasswd(obfPwd);
      passwd.append(PlainPasswd);
      passwd.setLength(PlainPasswd.length());
      return true;
    }

    if (user == null) {
      dlg = new PasswdDialog(title, (user == null), null, (passwd == null));
    } else {
      String userName = viewer.user.getValue();
      if (viewer.sendLocalUsername.getValue()) {
        userName = (String)System.getProperties().get("user.name");
        if (passwd == null)
          return true;
      }
      dlg = new PasswdDialog(title, (userName != null), userName,
            (passwd == null));
    }
    if (!dlg.showDialog()) return false;
    if (user != null)
        user.append(dlg.userEntry.getText());
    if (passwd != null)
      passwd.append(dlg.passwdEntry.getText());
    return true;
  }

  // CConnection callback methods

  // serverInit() is called when the serverInit message has been received.  At
  // this point we create the desktop window and display it.  We also tell the
  // server the pixel format and encodings to use and request the first update.
  public void serverInit() {
    super.serverInit();

    serverPF = cp.pf();

    desktop = new DesktopWindow(cp.width, cp.height, serverPF, this);
    fullColourPF = desktop.getPreferredPF();

    // Force a switch to the format and encoding we'd like
    formatChange = true; encodingChange = true;

    // And kick off the update cycle
    requestNewUpdate();

    // This initial update request is a bit of a corner case, so we need
    // to help out setting the correct format here.
    assert(pendingPFChange);
    desktop.setServerPF(pendingPF);
    cp.setPF(pendingPF);
    pendingPFChange = false;

    recreateViewport();
  }

  // setDesktopSize() is called when the desktop size changes (including when
  // it is set initially).
  public void setDesktopSize(int w, int h) {
    super.setDesktopSize(w,h);
    resizeFramebuffer();
  }

  // setExtendedDesktopSize() is a more advanced version of setDesktopSize()
  public void setExtendedDesktopSize(int reason, int result, int w, int h,
                                     ScreenSet layout) {
    super.setExtendedDesktopSize(reason, result, w, h, layout);

    if ((reason == screenTypes.reasonClient) &&
        (result != screenTypes.resultSuccess)) {
      vlog.error("SetDesktopSize failed: "+result);
      return;
    }

    resizeFramebuffer();
  }

  // clientRedirect() migrates the client to another host/port
  public void clientRedirect(int port, String host, 
                             String x509subject) {
    try {
      sock.close();
      setServerPort(port);
      sock = new TcpSocket(host, port);
      vlog.info("Redirected to "+host+":"+port);
      setStreams(sock.inStream(), sock.outStream());
      sock.inStream().setBlockCallback(this);
      initialiseProtocol();
    } catch (java.lang.Exception e) {
      throw new Exception(e.toString());
    }
  }

  // setName() is called when the desktop name changes
  public void setName(String name) {
    super.setName(name);
  
    if (viewport != null) {
      viewport.setTitle(name+" - TurboVNC");
    }
  }

  // framebufferUpdateStart() is called at the beginning of an update.
  // Here we try to send out a new framebuffer update request so that the
  // next update can be sent out in parallel with us decoding the current
  // one. 
  public void framebufferUpdateStart() 
  {
    // Note: This might not be true if sync fences are supported
    pendingUpdate = false;

    requestNewUpdate();
  }

  // framebufferUpdateEnd() is called at the end of an update.
  // For each rectangle, the FdInStream will have timed the speed
  // of the connection, allowing us to select format and encoding
  // appropriately, and then request another incremental update.
  public void framebufferUpdateEnd() 
  {

    desktop.updateWindow();

    if (firstUpdate) {
      int width, height;
      
      // We need fences to make extra update requests and continuous
      // updates "safe". See fence() for the next step.
      if (cp.supportsFence)
        writer().writeFence(fenceTypes.fenceFlagRequest | fenceTypes.fenceFlagSyncNext, 0, null);

      if (cp.supportsSetDesktopSize &&
          viewer.desktopSize.getValueStr() != null &&
          viewer.desktopSize.getValueStr().split("x").length == 2) {
        width = Integer.parseInt(viewer.desktopSize.getValue().split("x")[0]);
        height = Integer.parseInt(viewer.desktopSize.getValue().split("x")[1]);
        ScreenSet layout;

        layout = cp.screenLayout;

        if (layout.num_screens() == 0)
          layout.add_screen(new Screen());
        else if (layout.num_screens() != 1) {

          while (true) {
            Iterator iter = layout.screens.iterator(); 
            Screen screen = (Screen)iter.next();
        
            if (!iter.hasNext())
              break;

            layout.remove_screen(screen.id);
          }
        }

        Screen screen0 = (Screen)layout.screens.iterator().next();
        screen0.dimensions.tl.x = 0;
        screen0.dimensions.tl.y = 0;
        screen0.dimensions.br.x = width;
        screen0.dimensions.br.y = height;

        writer().writeSetDesktopSize(width, height, layout);
      }

      firstUpdate = false;
    }

    // A format change has been scheduled and we are now past the update
    // with the old format. Time to active the new one.
    if (pendingPFChange) {
      desktop.setServerPF(pendingPF);
      cp.setPF(pendingPF);
      pendingPFChange = false;
    }
  }

  // The rest of the callbacks are fairly self-explanatory...

  public void setColourMapEntries(int firstColour, int nColours, int[] rgbs) {
    desktop.setColourMapEntries(firstColour, nColours, rgbs);
  }

  public void bell() { 
    if (viewer.acceptBell.getValue())
      desktop.getToolkit().beep(); 
  }

  public void serverCutText(String str, int len) {
    if (viewer.acceptClipboard.getValue())
      clipboardDialog.serverCutText(str, len);
  }

  // We start timing on beginRect and stop timing on endRect, to
  // avoid skewing the bandwidth estimation as a result of the server
  // being slow or the network having high latency
  public void beginRect(Rect r, int encoding) {
    sock.inStream().startTiming();
    if (encoding != Encodings.encodingCopyRect) {
      lastServerEncoding = encoding;
    }
  }

  public void endRect(Rect r, int encoding) {
    sock.inStream().stopTiming();
  }

  public void fillRect(Rect r, int p) {
    desktop.fillRect(r.tl.x, r.tl.y, r.width(), r.height(), p);
  }

  public void imageRect(Rect r, Object p) {
    desktop.imageRect(r.tl.x, r.tl.y, r.width(), r.height(), p);
  }

  public void copyRect(Rect r, int sx, int sy) {
    desktop.copyRect(r.tl.x, r.tl.y, r.width(), r.height(), sx, sy);
  }

  public void setCursor(int width, int height, Point hotspot,
                        int[] data, byte[] mask) {
    desktop.setCursor(width, height, hotspot, data, mask);
  }

  public void fence(int flags, int len, byte[] data)
  {
    // can't call super.super.fence(flags, len, data);
    cp.supportsFence = true;
  
    if ((flags & fenceTypes.fenceFlagRequest) != 0) {
      // We handle everything synchronously so we trivially honor these modes
      flags = flags & (fenceTypes.fenceFlagBlockBefore | fenceTypes.fenceFlagBlockAfter);
  
      writer().writeFence(flags, len, data);
      return;
    }
  
    if (len == 0) {
      // Initial probe
      if ((flags & fenceTypes.fenceFlagSyncNext) != 0) {
        supportsSyncFence = true;
  
        if (cp.supportsContinuousUpdates) {
          vlog.info("Enabling continuous updates");
          continuousUpdates = true;
          writer().writeEnableContinuousUpdates(true, 0, 0, cp.width, cp.height);
        }
      }
    } else {
      // Pixel format change
      MemInStream memStream = new MemInStream(data, 0, len);
      PixelFormat pf = new PixelFormat();
  
      pf.read(memStream);
  
      desktop.setServerPF(pf);
      cp.setPF(pf);
    }
  }

  private void resizeFramebuffer()
  {
    if (desktop == null)
      return;

    if (continuousUpdates)
      writer().writeEnableContinuousUpdates(true, 0, 0, cp.width, cp.height);

    if ((cp.width == 0) && (cp.height == 0))
      return;
    if ((desktop.width() == cp.width) && (desktop.height() == cp.height))
      return;
    
    desktop.resize();
    recreateViewport();
  }

  // recreateViewport() recreates our top-level window.  This seems to be
  // better than attempting to resize the existing window, at least with
  // various X window managers.

  private void recreateViewport()
  {
    if (viewport != null) viewport.dispose();
    viewport = new Viewport(cp.name(), this);
    viewport.setUndecorated(fullScreen);
    desktop.setViewport(viewport);
    ClassLoader loader = this.getClass().getClassLoader();
    URL url = loader.getResource("com/turbovnc/vncviewer/turbovnc.ico");
    ImageIcon icon = null;
    if (url != null) {
      icon = new ImageIcon(url);
      viewport.setIconImage(icon.getImage());
    }
    viewport.addChild(desktop);
    reconfigureViewport();
    if ((cp.width > 0) && (cp.height > 0))
      viewport.setVisible(true);
    desktop.initGraphics();
    desktop.requestFocusInWindow();
  }

  private void reconfigureViewport()
  {
    //viewport.setMaxSize(cp.width, cp.height);
    boolean pack = true;
    Dimension dpySize = viewport.getToolkit().getScreenSize();
    desktop.setScaledSize();
    int w = desktop.scaledWidth;
    int h = desktop.scaledHeight;
    if (fullScreen) {
      viewport.setExtendedState(JFrame.MAXIMIZED_BOTH);
      viewport.setGeometry(0, 0, dpySize.width, dpySize.height, false);
    } else {
      int wmDecorationWidth = viewport.getInsets().left + viewport.getInsets().right;
      int wmDecorationHeight = viewport.getInsets().top + viewport.getInsets().bottom;
      if (w + wmDecorationWidth >= dpySize.width) {
        w = dpySize.width - wmDecorationWidth;
        pack = false;
      }
      if (h + wmDecorationHeight >= dpySize.height) {
        h = dpySize.height - wmDecorationHeight;
        pack = false;
      }

      if (viewport.getExtendedState() == JFrame.MAXIMIZED_BOTH) {
        w = viewport.getSize().width;
        h = viewport.getSize().height;
        int x = viewport.getLocation().x;
        int y = viewport.getLocation().y;
        viewport.setGeometry(x, y, w, h, pack);
      } else {
        int x = (dpySize.width - w - wmDecorationWidth) / 2;
        int y = (dpySize.height - h - wmDecorationHeight)/2;
        viewport.setExtendedState(JFrame.NORMAL);
        viewport.setGeometry(x, y, w, h, pack);
      }
    }
  }

  // requestNewUpdate() requests an update from the server, having set the
  // format and encoding appropriately.
  private void requestNewUpdate()
  {
    if (formatChange) {
      PixelFormat pf;

      /* Catch incorrect requestNewUpdate calls */
      assert(!pendingUpdate || supportsSyncFence);

      if (fullColour) {
        pf = fullColourPF;
      } else {
        if (lowColourLevel == 0) {
          pf = verylowColourPF;
        } else if (lowColourLevel == 1) {
          pf = lowColourPF;
        } else {
          pf = mediumColourPF;
        }
      }

      if (supportsSyncFence) {
        // We let the fence carry the pixel format and switch once we
        // get the response back. That way we will be synchronised with
        // when the server switches.
        MemOutStream memStream = new MemOutStream();
  
        pf.write(memStream);
  
        writer().writeFence(fenceTypes.fenceFlagRequest | fenceTypes.fenceFlagSyncNext,
                            memStream.length(), (byte[])memStream.data());
      } else {
        // New requests are sent out at the start of processing the last
        // one, so we cannot switch our internal format right now (doing so
        // would mean misdecoding the current update).
        pendingPFChange = true;
        pendingPF = pf;
      }

      String str = pf.print();
      vlog.info("Using pixel format "+str);
      writer().writeSetPixelFormat(pf);

      formatChange = false;
    }

    checkEncodings();

    if (forceNonincremental || !continuousUpdates) {
      pendingUpdate = true;
      writer().writeFramebufferUpdateRequest(new Rect(0,0,cp.width,cp.height),
                                                 !formatChange);
    }

    forceNonincremental = false;
  }


  ////////////////////////////////////////////////////////////////////
  // The following methods are all called from the GUI thread

  // close() closes the socket, thus waking up the RFB thread.
  public void close() {
    shuttingDown = true;
    sock.shutdown();
  }

  // Menu callbacks.  These are guaranteed only to be called after serverInit()
  // has been called, since the menu is only accessible from the DesktopWindow

  private void initMenu() {
    menu = new F8Menu(this);
  }

  void showMenu(int x, int y) {
    String os = System.getProperty("os.name");
    if (os.startsWith("Windows"))
      com.sun.java.swing.plaf.windows.WindowsLookAndFeel.setMnemonicHidden(false);
    menu.show(desktop, x, y);
  }

  void showAbout() {
    InputStream stream = cl.getResourceAsStream("com/turbovnc/vncviewer/timestamp");
    String pkgDate = "";
    String pkgTime = "";
    try {
      Manifest manifest = new Manifest(stream);
      Attributes attributes = manifest.getMainAttributes();
      pkgDate = attributes.getValue("Package-Date");
      pkgTime = attributes.getValue("Package-Time");
    } catch (IOException e) { }
    JOptionPane.showMessageDialog((viewport != null ? viewport : null),
      VncViewer.about1+" v"+VncViewer.version+" ("+VncViewer.build+")\n"
      +"Built on "+pkgDate+" at "+pkgTime+"\n"
      +VncViewer.about2+"\n"
      +VncViewer.about3,
      "About TurboVNC Viewer",
      JOptionPane.INFORMATION_MESSAGE,
      logo);
  }

  void showInfo() {
    JOptionPane.showMessageDialog(viewport,
      "Desktop name: "+cp.name()+"\n"
      +"Host: "+serverHost+":"+serverPort+"\n"
      +"Size: "+cp.width+"x"+cp.height+"\n"
      +"Pixel format: "+desktop.getPF().print()+"\n"
      +"(server default "+serverPF.print()+")\n"
      +"Requested encoding: "+Encodings.encodingName(currentEncoding)+"\n"
      +"Last used encoding: "+Encodings.encodingName(lastServerEncoding)+"\n"
      +"Line speed estimate: "+sock.inStream().kbitsPerSecond()+" kbit/s"+"\n"
      +"Protocol version: "+cp.majorVersion+"."+cp.minorVersion+"\n"
      +"Security method: "+Security.secTypeName(csecurity.getType())
       +" ["+csecurity.description()+"]",
      "VNC connection info",
      JOptionPane.PLAIN_MESSAGE);
  }

  public void refresh() {
    writer().writeFramebufferUpdateRequest(new Rect(0,0,cp.width,cp.height), false);
    pendingUpdate = true;
  }


  // OptionsDialogCallback.  setOptions() sets the options dialog's checkboxes
  // etc to reflect our flags.  getOptions() sets our flags according to the
  // options dialog's checkboxes.  They are both called from the GUI thread.
  // Some of the flags are also accessed by the RFB thread.  I believe that
  // reading and writing boolean and int values in java is atomic, so there is
  // no need for synchronization.

  public boolean isUnixLoginSelected() {
    return options.secUnixLogin.isSelected();
  }

  public boolean isUnixLoginForced() {
    return (viewer.user.getValue() != null ||
            viewer.sendLocalUsername.getValue());
  }

  public void setOptions() {
    int digit;
    options.allowJpeg.setSelected(viewer.allowJpeg.getValue());
    options.subsamplingLevel.setValue(cp.getSubsamplingOrdinal());
    options.jpegQualityLevel.setValue(cp.quality);
    options.zlibCompressionLevel.setValue(cp.compressLevel);

    if (currentEncoding != Encodings.encodingTight) {
      options.allowJpeg.setEnabled(false);
      options.subsamplingLevel.setEnabled(false);
      options.jpegQualityLevel.setEnabled(false);
      options.zlibCompressionLevel.setEnabled(false);
      options.encMethodComboBox.insertItemAt(Encodings.encodingName(currentEncoding), 0);
      options.encMethodComboBox.setSelectedItem(Encodings.encodingName(currentEncoding));
      options.encMethodComboBox.setEnabled(false);
    }

    options.viewOnly.setSelected(viewer.viewOnly.getValue());
    options.acceptClipboard.setSelected(viewer.acceptClipboard.getValue());
    options.sendClipboard.setSelected(viewer.sendClipboard.getValue());
    options.menuKey.setSelectedIndex(menuKey-0xFFBE);

    if (state() == RFBSTATE_NORMAL) {
      options.shared.setEnabled(false);
      options.secVeNCrypt.setEnabled(false);
      options.encNone.setEnabled(false);
      options.encTLS.setEnabled(false);
      options.encX509.setEnabled(false);
      options.ca.setEnabled(false);
      options.crl.setEnabled(false);
      options.secIdent.setEnabled(false);
      options.secNone.setEnabled(false);
      options.secVnc.setEnabled(false);
      options.secUnixLogin.setEnabled(false);
      options.secPlain.setEnabled(false);
      options.sendLocalUsername.setEnabled(false);
    } else {
      options.shared.setSelected(viewer.shared.getValue());
      options.sendLocalUsername.setSelected(viewer.sendLocalUsername.getValue());
      options.secUnixLogin.setSelected(!viewer.noUnixLogin.getValue());
      
      /* Process non-VeNCrypt sectypes */
      java.util.List<Integer> secTypes = new ArrayList<Integer>();
      secTypes = Security.GetEnabledSecTypes();
      for (Iterator i = secTypes.iterator(); i.hasNext();) {
        switch ((Integer)i.next()) {
        case Security.secTypeVeNCrypt:
          options.secVeNCrypt.setSelected(true);
          break;
        case Security.secTypeNone:
          options.secNone.setSelected(true);
          break;
        case Security.secTypeVncAuth:
          options.secVnc.setSelected(true);
          break;
        }
      }

      /* Process VeNCrypt subtypes */
      if (options.secVeNCrypt.isSelected()) {
        java.util.List<Integer> secTypesExt = new ArrayList<Integer>();
        secTypesExt = Security.GetEnabledExtSecTypes();
        for (Iterator iext = secTypesExt.iterator(); iext.hasNext();) {
          switch ((Integer)iext.next()) {
          case Security.secTypePlain:
            options.encNone.setSelected(true);
            options.secPlain.setSelected(true);
            break;
          case Security.secTypeIdent:
            options.encNone.setSelected(true);
            options.secIdent.setSelected(true);
            break;
          case Security.secTypeTLSNone:
            options.encTLS.setSelected(true);
            options.secNone.setSelected(true);
            break;
          case Security.secTypeTLSVnc:
            options.encTLS.setSelected(true);
            options.secVnc.setSelected(true);
            break;
          case Security.secTypeTLSPlain:
            options.encTLS.setSelected(true);
            options.secPlain.setSelected(true);
            break;
          case Security.secTypeTLSIdent:
            options.encTLS.setSelected(true);
            options.secIdent.setSelected(true);
            break;
          case Security.secTypeX509None:
            options.encX509.setSelected(true);
            options.secNone.setSelected(true);
            break;
          case Security.secTypeX509Vnc:
            options.encX509.setSelected(true);
            options.secVnc.setSelected(true);
            break;
          case Security.secTypeX509Plain:
            options.encX509.setSelected(true);
            options.secPlain.setSelected(true);
            break;
          case Security.secTypeX509Ident:
            options.encX509.setSelected(true);
            options.secIdent.setSelected(true);
            break;
          }
        }
      } else {
        options.encNone.setEnabled(false);
        options.encTLS.setEnabled(false);
        options.encX509.setEnabled(false);
        options.ca.setEnabled(false);
        options.crl.setEnabled(false);
        options.secIdent.setEnabled(false);
        options.secPlain.setEnabled(false);
      }
      options.sendLocalUsername.setEnabled(
        (options.secIdent.isSelected() && options.secIdent.isEnabled()) ||
        (options.secPlain.isSelected() && options.secPlain.isEnabled()) ||
        (options.secUnixLogin.isSelected() && options.secUnixLogin.isEnabled()));
    }

    options.fullScreen.setSelected(fullScreen);
    options.useLocalCursor.setSelected(viewer.useLocalCursor.getValue());
    options.fastCopyRect.setSelected(viewer.fastCopyRect.getValue());
    options.acceptBell.setSelected(viewer.acceptBell.getValue());
    String scaleString = viewer.scalingFactor.getValue();
    if (scaleString.equals("Auto")) {
      options.scalingFactor.setSelectedItem("Auto");
    } else if(scaleString.equals("FixedRatio")) {
      options.scalingFactor.setSelectedItem("Fixed Aspect Ratio");
    } else { 
      digit = Integer.parseInt(scaleString);
      if (digit >= 1 && digit <= 1000) {
        options.scalingFactor.setSelectedItem(digit+"%");
      } else {
        digit = Integer.parseInt(viewer.scalingFactor.getDefaultStr());
        options.scalingFactor.setSelectedItem(digit+"%");
      }
      int scaleFactor = 
        Integer.parseInt(scaleString.substring(0, scaleString.length()));
      if (desktop != null)
        desktop.setScaledSize();
    }
  }

  public void getOptions() {
    if (fullColour != true)
      formatChange = true;
    fullColour = true;
    viewer.allowJpeg.setParam(options.allowJpeg.isSelected());
    if (cp.allowJpeg != viewer.allowJpeg.getValue()) {
      cp.allowJpeg = viewer.allowJpeg.getValue();
      encodingChange = true;
    }
    if (cp.quality != options.jpegQualityLevel.getValue()) {
      cp.quality = options.jpegQualityLevel.getValue();
      encodingChange = true;
    }
    if (cp.compressLevel != options.zlibCompressionLevel.getValue()) {
      cp.compressLevel = options.zlibCompressionLevel.getValue();
      encodingChange = true;
    }
    int subsampling = options.getSubsamplingLevel();
    if (cp.subsampling != subsampling) {
      cp.subsampling = subsampling;
      encodingChange = true;
    }
    viewer.sendLocalUsername.setParam(options.sendLocalUsername.isSelected());

    viewer.viewOnly.setParam(options.viewOnly.isSelected());
    viewer.acceptClipboard.setParam(options.acceptClipboard.isSelected());
    viewer.sendClipboard.setParam(options.sendClipboard.isSelected());
    viewer.fastCopyRect.setParam(options.fastCopyRect.isSelected());
    viewer.acceptBell.setParam(options.acceptBell.isSelected());
    String scaleString =
      options.scalingFactor.getSelectedItem().toString();
    String oldScaleFactor = viewer.scalingFactor.getValue();
    if (scaleString.equals("Auto")) {
      if (!oldScaleFactor.equals(scaleString)) {
      viewer.scalingFactor.setParam("Auto");
        if (desktop != null)
          reconfigureViewport();
      }
    } else if(scaleString.equals("Fixed Aspect Ratio")) {
      if (!oldScaleFactor.equals("FixedRatio")) {
        viewer.scalingFactor.setParam("FixedRatio");
        if (desktop != null)
          reconfigureViewport();
      }
    } else { 
      scaleString=scaleString.substring(0, scaleString.length()-1);
      if (!oldScaleFactor.equals(scaleString)) {
        viewer.scalingFactor.setParam(scaleString);
        if ((desktop != null) && (!oldScaleFactor.equals("Auto") ||
            !oldScaleFactor.equals("FixedRatio"))) {
          reconfigureViewport();
        }
      }
    }

    clipboardDialog.setSendingEnabled(viewer.sendClipboard.getValue());
    menuKey = (options.menuKey.getSelectedIndex()+0xFFBE);
    F8Menu.f8.setText("Send F"+(menuKey-Keysyms.F1+1));

    setShared(options.shared.isSelected());
    viewer.useLocalCursor.setParam(options.useLocalCursor.isSelected());
    if (cp.supportsLocalCursor != viewer.useLocalCursor.getValue()) {
      cp.supportsLocalCursor = viewer.useLocalCursor.getValue();
      encodingChange = true;
      if (desktop != null)
        desktop.resetLocalCursor();
    }
    
    checkEncodings();
  
    if (state() != RFBSTATE_NORMAL) {
      Security.DisableSecType(Security.secTypeNone);
      Security.DisableSecType(Security.secTypeVncAuth);
      Security.DisableSecType(Security.secTypePlain);
      Security.DisableSecType(Security.secTypeIdent);
      Security.DisableSecType(Security.secTypeTLSNone);
      Security.DisableSecType(Security.secTypeTLSVnc);
      Security.DisableSecType(Security.secTypeTLSPlain);
      Security.DisableSecType(Security.secTypeTLSIdent);
      Security.DisableSecType(Security.secTypeX509None);
      Security.DisableSecType(Security.secTypeX509Vnc);
      Security.DisableSecType(Security.secTypeX509Plain);
      Security.DisableSecType(Security.secTypeX509Ident);

      /* Process security types which don't use encryption */
      if (options.encNone.isSelected() || !options.secVeNCrypt.isSelected()) {
        if (options.secNone.isSelected())
          Security.EnableSecType(Security.secTypeNone);
 
        if (options.secVnc.isSelected())
          Security.EnableSecType(Security.secTypeVncAuth);
      }

      if (options.encNone.isSelected() && options.secVeNCrypt.isSelected()) {
        if (options.secPlain.isSelected())
          Security.EnableSecType(Security.secTypePlain);
 
        if (options.secIdent.isSelected())
          Security.EnableSecType(Security.secTypeIdent);
      }

      /* Process security types which use TLS encryption */
      if (options.encTLS.isSelected() && options.secVeNCrypt.isSelected()) {
        if (options.secNone.isSelected())
          Security.EnableSecType(Security.secTypeTLSNone);

        if (options.secVnc.isSelected())
          Security.EnableSecType(Security.secTypeTLSVnc);

        if (options.secPlain.isSelected())
          Security.EnableSecType(Security.secTypeTLSPlain);

        if (options.secIdent.isSelected())
          Security.EnableSecType(Security.secTypeTLSIdent);
      }

      /* Process security types which use X509 encryption */
      if (options.encX509.isSelected() && options.secVeNCrypt.isSelected()) {
        if (options.secNone.isSelected())
          Security.EnableSecType(Security.secTypeX509None);

        if (options.secVnc.isSelected())
          Security.EnableSecType(Security.secTypeX509Vnc);

        if (options.secPlain.isSelected())
          Security.EnableSecType(Security.secTypeX509Plain);

        if (options.secIdent.isSelected())
          Security.EnableSecType(Security.secTypeX509Ident);
      }

      CSecurityTLS.x509ca.setParam(options.ca.getText());
      CSecurityTLS.x509crl.setParam(options.crl.getText());
    }
  }

  public void toggleFullScreen() {
    fullScreen = !fullScreen;
    if (!fullScreen) menu.fullScreen.setSelected(false);
    recreateViewport();
  }

  // writeClientCutText() is called from the clipboard dialog
  public void writeClientCutText(String str, int len) {
    if (state() != RFBSTATE_NORMAL) return;
    writer().writeClientCutText(str,len);
  }

  public void writeKeyEvent(int keysym, boolean down) {
    if (state() != RFBSTATE_NORMAL) return;
    writer().writeKeyEvent(keysym, down);
  }

  public void writeKeyEvent(KeyEvent ev) {
    if (ev.getID() != KeyEvent.KEY_PRESSED && !ev.isActionKey())
      return;

    int keysym, keycode, currentModifiers;

    currentModifiers = ev.getModifiers();
    keycode = ev.getKeyCode();

    if (!ev.isActionKey()) {
      vlog.debug("key press "+ev.getKeyChar());
      if (ev.getKeyChar() < 32) {
        // if the ctrl modifier key is down, send the equivalent ASCII since we
        // will send the ctrl modifier anyway

        if ((currentModifiers & KeyEvent.CTRL_MASK) != 0) {
          if ((currentModifiers & KeyEvent.SHIFT_MASK) != 0) {
            keysym = ev.getKeyChar() + 64;
            if (keysym == -1)
              return;
          } else { 
            keysym = ev.getKeyChar() + 96;
            if (keysym == 127) keysym = 95;
          }
        } else {
          switch (keycode) {
          case KeyEvent.VK_BACK_SPACE: keysym = Keysyms.BackSpace; break;
          case KeyEvent.VK_TAB:        keysym = Keysyms.Tab; break;
          case KeyEvent.VK_ENTER:      keysym = Keysyms.Return; break;
          case KeyEvent.VK_ESCAPE:     keysym = Keysyms.Escape; break;
          default: return;
          }
        }

      } else if (ev.getKeyChar() == 127) {
        keysym = Keysyms.Delete;

      } else {
        keysym = UnicodeToKeysym.translate(ev.getKeyChar());
        if (keysym == -1)
          return;

        // Windows 7 or some Java version send key events that require the
        // following special treatment with the German Alt-Gr Key. They send
        // ALT + CTRL before the normal key event. They should be suppressed
        if ((currentModifiers & KeyEvent.CTRL_MASK) != 0
		&& (currentModifiers & KeyEvent.ALT_MASK) != 0
		&& ((keysym == 0x5c) || (keysym == 0x7c)	// backslash bar
		 || (keysym == 0x5b) || (keysym == 0x5d)	// [ ]
		 || (keysym == 0x7b) || (keysym == 0x7d)	// { }
		 || (keysym == 0x7e) || (keysym == 0x40)	// ~ @
		 || (keysym == 0x20ac) || (keysym == 0xb5)	// Euro Micro
		 || (keysym == 0xb2) || (keysym == 0xb3))	// ^2 ^3
	           )
          currentModifiers &= (~ KeyEvent.CTRL_MASK) & (~ KeyEvent.ALT_MASK);
      }

    } else {
      // KEY_ACTION
      vlog.debug("key action " + keycode);
      switch (keycode) {
      case KeyEvent.VK_HOME:         keysym = Keysyms.Home; break;
      case KeyEvent.VK_END:          keysym = Keysyms.End; break;
      case KeyEvent.VK_PAGE_UP:      keysym = Keysyms.Page_Up; break;
      case KeyEvent.VK_PAGE_DOWN:    keysym = Keysyms.Page_Down; break;
      case KeyEvent.VK_UP:           keysym = Keysyms.Up; break;
      case KeyEvent.VK_DOWN:         keysym = Keysyms.Down; break;
      case KeyEvent.VK_LEFT:         keysym = Keysyms.Left; break;
      case KeyEvent.VK_RIGHT:        keysym = Keysyms.Right; break;
      case KeyEvent.VK_F1:           keysym = Keysyms.F1; break;
      case KeyEvent.VK_F2:           keysym = Keysyms.F2; break;
      case KeyEvent.VK_F3:           keysym = Keysyms.F3; break;
      case KeyEvent.VK_F4:           keysym = Keysyms.F4; break;
      case KeyEvent.VK_F5:           keysym = Keysyms.F5; break;
      case KeyEvent.VK_F6:           keysym = Keysyms.F6; break;
      case KeyEvent.VK_F7:           keysym = Keysyms.F7; break;
      case KeyEvent.VK_F8:           keysym = Keysyms.F8; break;
      case KeyEvent.VK_F9:           keysym = Keysyms.F9; break;
      case KeyEvent.VK_F10:          keysym = Keysyms.F10; break;
      case KeyEvent.VK_F11:          keysym = Keysyms.F11; break;
      case KeyEvent.VK_F12:          keysym = Keysyms.F12; break;
      case KeyEvent.VK_PRINTSCREEN:  keysym = Keysyms.Print; break;
      case KeyEvent.VK_PAUSE:        keysym = Keysyms.Pause; break;
      case KeyEvent.VK_INSERT:       keysym = Keysyms.Insert; break;
      default: return;
      }
    }

    writeModifiers(currentModifiers);
    writeKeyEvent(keysym, true);
    writeKeyEvent(keysym, false);
    writeModifiers(0);
  }


  public void writePointerEvent(MouseEvent ev) {
    if (state() != RFBSTATE_NORMAL) return;

    switch (ev.getID()) {
    case MouseEvent.MOUSE_PRESSED:
      buttonMask = 1;
      if ((ev.getModifiers() & KeyEvent.ALT_MASK) != 0) buttonMask = 2;
      if ((ev.getModifiers() & KeyEvent.META_MASK) != 0) buttonMask = 4;
      break;
    case MouseEvent.MOUSE_RELEASED:
      buttonMask = 0;
      break;
    }

    writeModifiers(ev.getModifiers() & ~KeyEvent.ALT_MASK & ~KeyEvent.META_MASK);

    if (cp.width != desktop.scaledWidth || 
        cp.height != desktop.scaledHeight) {
      int sx = (desktop.scaleWidthRatio == 1.00) 
        ? ev.getX() : (int)Math.floor(ev.getX()/desktop.scaleWidthRatio);
      int sy = (desktop.scaleHeightRatio == 1.00) 
        ? ev.getY() : (int)Math.floor(ev.getY()/desktop.scaleHeightRatio);
      ev.translatePoint(sx - ev.getX(), sy - ev.getY());
    }
    
    writer().writePointerEvent(new Point(ev.getX(),ev.getY()), buttonMask);

    if (buttonMask == 0) writeModifiers(0);
  }


  public void writeWheelEvent(MouseWheelEvent ev) {
    if (state() != RFBSTATE_NORMAL) return;
    int x, y;
    int clicks = ev.getWheelRotation();
    if (clicks < 0) {
      buttonMask = 8;
    } else {
      buttonMask = 16;
    }
    writeModifiers(ev.getModifiers() & ~KeyEvent.ALT_MASK & ~KeyEvent.META_MASK);
    for (int i=0;i<Math.abs(clicks);i++) {
      x = ev.getX();
      y = ev.getY();
      writer().writePointerEvent(new Point(x, y), buttonMask);
      buttonMask = 0;
      writer().writePointerEvent(new Point(x, y), buttonMask);
    }
    writeModifiers(0);

  }


  synchronized void writeModifiers(int m) {
    if ((m & Event.SHIFT_MASK) != (pressedModifiers & Event.SHIFT_MASK))
      writeKeyEvent(Keysyms.Shift_L, (m & Event.SHIFT_MASK) != 0);
    if ((m & Event.CTRL_MASK) != (pressedModifiers & Event.CTRL_MASK))
      writeKeyEvent(Keysyms.Control_L, (m & Event.CTRL_MASK) != 0);
    if ((m & Event.ALT_MASK) != (pressedModifiers & Event.ALT_MASK))
      writeKeyEvent(Keysyms.Alt_L, (m & Event.ALT_MASK) != 0);
    if ((m & Event.META_MASK) != (pressedModifiers & Event.META_MASK))
      writeKeyEvent(Keysyms.Meta_L, (m & Event.META_MASK) != 0);
    pressedModifiers = m;
  }


  ////////////////////////////////////////////////////////////////////
  // The following methods are called from both RFB and GUI threads

  // checkEncodings() sends a setEncodings message if one is needed.
  private void checkEncodings() {
    if (encodingChange && (writer() != null)) {
      vlog.info("Using "+Encodings.encodingName(currentEncoding)+" encoding");
      writer().writeSetEncodings(currentEncoding, true);
      encodingChange = false;
    }
  }

  // the following never change so need no synchronization:


  // viewer object is only ever accessed by the GUI thread so needs no
  // synchronization (except for one test in DesktopWindow - see comment
  // there).
  VncViewer viewer;

  // access to desktop by different threads is specified in DesktopWindow

  // the following need no synchronization:

  ClassLoader cl = this.getClass().getClassLoader();
  ImageIcon logo = new ImageIcon(cl.getResource("com/turbovnc/vncviewer/turbovnc.png"));
  public static UserPasswdGetter upg;
  public UserMsgBox msg;

  // shuttingDown is set by the GUI thread and only ever tested by the RFB
  // thread after the window has been destroyed.
  boolean shuttingDown;

  // reading and writing int and boolean is atomic in java, so no
  // synchronization of the following flags is needed:
  
  int lowColourLevel;


  // All menu, options, about and info stuff is done in the GUI thread (apart
  // from when constructed).
  F8Menu menu;
  OptionsDialog options;

  // clipboard sync issues?
  ClipboardDialog clipboardDialog;

  // the following are only ever accessed by the GUI thread:
  int buttonMask;
  int pressedModifiers;

  private String serverHost;
  private int serverPort;
  private Socket sock;

  protected DesktopWindow desktop;

  // FIXME: should be private
  public PixelFormat serverPF;
  private PixelFormat fullColourPF;

  private boolean pendingPFChange;
  private PixelFormat pendingPF;

  private int currentEncoding, lastServerEncoding;

  private boolean formatChange;
  private boolean encodingChange;

  private boolean firstUpdate;
  private boolean pendingUpdate;
  private boolean continuousUpdates;

  private boolean forceNonincremental;

  private boolean supportsSyncFence;

  public int menuKey;
  Viewport viewport;
  private boolean fullColour;
  boolean fullScreen;
  
  static LogWriter vlog = new LogWriter("CConn");
}