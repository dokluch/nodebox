package nodebox.client;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterators;
import com.google.common.collect.Maps;
import nodebox.node.Connection;
import nodebox.node.Node;
import nodebox.node.NodePort;
import nodebox.node.Port;
import nodebox.ui.PaneView;
import nodebox.ui.Platform;
import nodebox.ui.Theme;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.AffineTransform;
import java.awt.geom.GeneralPath;
import java.awt.geom.NoninvertibleTransformException;
import java.awt.geom.Point2D;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.List;

import static com.google.common.base.Preconditions.checkNotNull;

public class NetworkView extends JComponent implements PaneView, KeyListener, MouseListener, MouseMotionListener {

    public static final int GRID_CELL_SIZE = 40;
    public static final int NODE_WIDTH = GRID_CELL_SIZE * 4 - 10;
    public static final int NODE_HEIGHT = GRID_CELL_SIZE - 10;
    public static final int PORT_WIDTH = 10;
    public static final int PORT_HEIGHT = 3;
    public static final int PORT_SPACING = 10;
    public static final Dimension NODE_DIMENSION = new Dimension(NODE_WIDTH, NODE_HEIGHT);

    public static final String SELECT_PROPERTY = "NetworkView.select";
    public static final String HIGHLIGHT_PROPERTY = "highlight";
    public static final String RENDER_PROPERTY = "render";
    public static final String NETWORK_PROPERTY = "network";

    public static final float MIN_ZOOM = 0.2f;
    public static final float MAX_ZOOM = 1.0f;

    public static final Color PORT_HOVER_COLOR = Color.YELLOW;
    public static final Map<String, Color> PORT_COLORS = Maps.newHashMap();
    public static final Color DEFAULT_PORT_COLOR = Color.WHITE;
    public static final Color NODE_BACKGROUND_COLOR = new Color(123, 154, 152);

    private static Cursor defaultCursor, panCursor;

    private final NodeBoxDocument document;

    private JPopupMenu networkMenu;

    // View state
    private AffineTransform viewTransform = new AffineTransform();
    private Set<String> selectedNodes = new HashSet<String>();

    // Interaction state
    private boolean isDraggingNodes = false;
    private boolean isPanningView = false;
    private boolean isShiftPressed = false;
    private ImmutableMap<String, nodebox.graphics.Point> dragPositions = ImmutableMap.of();
    private NodePort overInput;
    private Node overOutput;
    private Node connectionOutput;
    private NodePort connectionInput;
    private Point2D connectionPoint;
    private boolean startDragging;
    private Point2D dragStartPoint;

    static {
        Image panCursorImage;

        try {
            if (Platform.onWindows())
                panCursorImage = ImageIO.read(new File("res/view-cursor-pan-32.png"));
            else
                panCursorImage = ImageIO.read(new File("res/view-cursor-pan.png"));
            Toolkit toolkit = Toolkit.getDefaultToolkit();
            panCursor = toolkit.createCustomCursor(panCursorImage, new Point(0, 0), "PanCursor");
            defaultCursor = Cursor.getDefaultCursor();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        PORT_COLORS.put(Port.TYPE_INT, Color.GRAY);
        PORT_COLORS.put(Port.TYPE_FLOAT, Color.GRAY);
        PORT_COLORS.put(Port.TYPE_STRING, Color.LIGHT_GRAY);
        PORT_COLORS.put(Port.TYPE_BOOLEAN, Color.DARK_GRAY);
        PORT_COLORS.put(Port.TYPE_POINT, Color.RED);
        PORT_COLORS.put(Port.TYPE_COLOR, Color.CYAN);
        PORT_COLORS.put("geometry", new Color(135, 136, 162));
        PORT_COLORS.put("list", Color.PINK);

    }

    public NetworkView(NodeBoxDocument document) {
        this.document = document;
        setBackground(Theme.NETWORK_BACKGROUND_COLOR);
        initEventHandlers();
        initMenus();
    }

    private void initEventHandlers() {
        setFocusable(true);
        // This is disabled so we can detect the tab key.
        setFocusTraversalKeysEnabled(false);
        addKeyListener(this);
        addMouseListener(this);
        addMouseMotionListener(this);
    }

    private void initMenus() {
        networkMenu = new JPopupMenu();
        networkMenu.add(new ResetViewAction());
        networkMenu.add(new GoUpAction());
    }

    public NodeBoxDocument getDocument() {
        return document;
    }

    public Node getActiveNetwork() {
        return document.getActiveNetwork();
    }

    //// Events ////

    /**
     * Refresh the nodes and connections cache.
     */
    public void updateAll() {
        updateNodes();
        updateConnections();
    }

    public void updateNodes() {
        repaint();
    }

    public void updateConnections() {
        repaint();
    }

    public void updatePosition(Node node) {
        updateConnections();
    }

    public void checkErrorAndRepaint() {
        //if (!networkError && !activeNetwork.hasError()) return;
        //networkError = activeNetwork.hasError();
        repaint();
    }

    public void codeChanged(Node node, boolean changed) {
        repaint();
    }

    //// Model queries ////

    public Node getActiveNode() {
        return document.getActiveNode();
    }

    private ImmutableList<Node> getNodes() {
        return getDocument().getActiveNetwork().getChildren();
    }

    private ImmutableList<Node> getNodesReversed() {
        return getNodes().reverse();
    }

    private Iterable<Connection> getConnections() {
        return getDocument().getActiveNetwork().getConnections();
    }

    //// Painting the nodes ////

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        // Draw background
        g2.setColor(Theme.NETWORK_BACKGROUND_COLOR);
        g2.fill(g.getClipBounds());

        // Paint the grid
        // (The grid is not really affected by the view transform)
        paintGrid(g2);

        // Set the view transform
        AffineTransform originalTransform = g2.getTransform();
        g2.transform(viewTransform);

        paintNodes(g2);
        paintConnections(g2);
        paintCurrentConnection(g2);

        // Restore original transform
        g2.setTransform(originalTransform);
    }

    private void paintGrid(Graphics2D g) {
        g.setColor(Theme.NETWORK_GRID_COLOR);
        int transformOffsetX = (int) (viewTransform.getTranslateX() % GRID_CELL_SIZE);
        int transformOffsetY = (int) (viewTransform.getTranslateY() % GRID_CELL_SIZE);

        for (int y = -GRID_CELL_SIZE; y < getHeight() + GRID_CELL_SIZE; y += GRID_CELL_SIZE) {
            g.drawLine(0, y - 5 + transformOffsetY, getWidth(), y - 5 + transformOffsetY);
        }
        for (int x = -GRID_CELL_SIZE; x < getWidth() + GRID_CELL_SIZE; x += GRID_CELL_SIZE) {
            g.drawLine(x - 5 + transformOffsetX, 0, x - 5 + transformOffsetX, getHeight());
        }
    }

    private void paintConnections(Graphics2D g) {
        g.setColor(Theme.CONNECTION_DEFAULT_COLOR);
        g.setStroke(new BasicStroke(2));
        for (Connection connection : getConnections()) {
            paintConnection(g, connection);
        }
    }

    private void paintConnection(Graphics2D g, Connection connection) {
        Node outputNode = findNodeWithName(connection.getOutputNode());
        Node inputNode = findNodeWithName(connection.getInputNode());
        Port inputPort = inputNode.getInput(connection.getInputPort());
        Rectangle outputRect = nodeRect(outputNode);
        Rectangle inputRect = nodeRect(inputNode);
        paintConnectionLine(g, outputRect.x + 4, outputRect.y + outputRect.height + 4, inputRect.x + portOffset(inputNode, inputPort) + 4, inputRect.y - 5);

    }

    private void paintCurrentConnection(Graphics2D g) {
        g.setColor(Theme.CONNECTION_DEFAULT_COLOR);
        if (connectionOutput != null) {
            Rectangle outputRect = nodeRect(connectionOutput);
            paintConnectionLine(g, outputRect.x + 4, outputRect.y + outputRect.height + 4, (int) connectionPoint.getX(), (int) connectionPoint.getY());
        }
    }

    private static void paintConnectionLine(Graphics2D g, int x0, int y0, int x1, int y1) {
        double dy = Math.abs(y1 - y0);
        if (dy < GRID_CELL_SIZE) {
            g.drawLine(x0, y0, x1, y1);
        } else {
            double halfDx = Math.abs(x1 - x0) / 2;
            GeneralPath p = new GeneralPath();
            p.moveTo(x0, y0);
            p.curveTo(x0, y0 + halfDx, x1, y1 - halfDx, x1, y1);
            g.draw(p);
        }
    }

    private void paintNodes(Graphics2D g) {
        g.setColor(Theme.NETWORK_NODE_NAME_COLOR);
        for (Node node : getNodes()) {
            Port hoverInputPort = overInput != null && overInput.node == node ? overInput.port : null;
            paintNode(g, node, isSelected(node), isRendered(node), hoverInputPort, overOutput == node);
        }
    }

    private static Color portTypeColor(String type) {
        Color portColor = PORT_COLORS.get(type);
        return portColor == null ? DEFAULT_PORT_COLOR : portColor;
    }

    private static void paintNode(Graphics2D g, Node node, boolean selected, boolean rendered, Port hoverInputPort, boolean hoverOutput) {
        Rectangle r = nodeRect(node);
        String outputType = node.getOutputType();

        // Draw selection ring
        if (selected) {
            g.setColor(Color.WHITE);
            g.fillRect(r.x, r.y, NODE_WIDTH, NODE_HEIGHT);
        }

        // Draw node
        g.setColor(portTypeColor(outputType));
        if (selected) {
            g.fillRect(r.x + 2, r.y + 2, NODE_WIDTH - 4, NODE_HEIGHT - 4);
        } else {
            g.fillRect(r.x, r.y, NODE_WIDTH, NODE_HEIGHT);
        }

        // Draw render flag
        if (rendered) {
            g.setColor(Color.WHITE);
            GeneralPath gp = new GeneralPath();
            gp.moveTo(r.x + NODE_WIDTH - 2, r.y + NODE_HEIGHT - 20);
            gp.lineTo(r.x + NODE_WIDTH - 2, r.y + NODE_HEIGHT - 2);
            gp.lineTo(r.x + NODE_WIDTH - 20, r.y + NODE_HEIGHT - 2);
            g.fill(gp);
        }

        // Draw input ports
        g.setColor(Color.WHITE);
        int portX = 0;
        for (Port input : node.getInputs()) {
            if (hoverInputPort == input) {
                g.setColor(PORT_HOVER_COLOR);
            } else {
                g.setColor(portTypeColor(input.getType()));
            }
            g.fillRect(r.x + portX, r.y - PORT_HEIGHT, PORT_WIDTH, PORT_HEIGHT);
            portX += PORT_WIDTH + PORT_SPACING;
        }

        // Draw output port
        if (hoverOutput) {
            g.setColor(PORT_HOVER_COLOR);
        } else {
            g.setColor(portTypeColor(outputType));
        }
        g.fillRect(r.x, r.y + NODE_HEIGHT, PORT_WIDTH, PORT_HEIGHT);

        g.setColor(Color.WHITE);
        g.fillRect(r.x + 5, r.y + 5, NODE_HEIGHT - 10, NODE_HEIGHT - 10);
        g.setColor(Color.WHITE);
        g.drawString(node.getName(), r.x + 30, r.y + 20);
    }

    private static Rectangle nodeRect(Node node) {
        return new Rectangle(nodePoint(node), NODE_DIMENSION);
    }

    private static Rectangle inputPortRect(Node node, Port port) {
        Point pt = nodePoint(node);
        Rectangle portRect = new Rectangle(pt.x + portOffset(node, port), pt.y - PORT_HEIGHT, PORT_WIDTH, PORT_HEIGHT);
        growHitRectangle(portRect);
        return portRect;
    }

    private static Rectangle outputPortRect(Node node) {
        Point pt = nodePoint(node);
        Rectangle portRect = new Rectangle(pt.x, pt.y + NODE_HEIGHT, PORT_WIDTH, PORT_HEIGHT);
        growHitRectangle(portRect);
        return portRect;
    }

    private static void growHitRectangle(Rectangle r) {
        r.grow(2, 2);
    }

    private static Point nodePoint(Node node) {
        int nodeX = ((int) node.getPosition().getX()) * GRID_CELL_SIZE;
        int nodeY = ((int) node.getPosition().getY()) * GRID_CELL_SIZE;
        return new Point(nodeX, nodeY);
    }

    private Point pointToGridPoint(Point e) {
        Point2D pt;
        try {
            pt = viewTransform.inverseTransform(e, null);
        } catch (NoninvertibleTransformException e1) {
            pt = e;
        }
        return new Point(
                (int) Math.floor(pt.getX() / GRID_CELL_SIZE),
                (int) Math.floor(pt.getY() / GRID_CELL_SIZE));
    }

    private static int portOffset(Node node, Port port) {
        int portIndex = node.getInputs().indexOf(port);
        return (PORT_WIDTH + PORT_SPACING) * portIndex;
    }

    //// View Transform ////

    private void resetViewTransform() {
        viewTransform = new AffineTransform();
        repaint();
    }

    //// View queries ////

    private Node findNodeWithName(String name) {
        return getActiveNetwork().getChild(name);
    }

    public Node getNodeAt(Point2D point) {
        for (Node node : getNodesReversed()) {
            Rectangle r = nodeRect(node);
            if (r.contains(point)) {
                return node;
            }
        }
        return null;
    }

    public Node getNodeAt(MouseEvent e) {
        return getNodeAt(e.getPoint());
    }

    public Node getNodeWithOutputPortAt(Point2D point) {
        for (Node node : getNodesReversed()) {
            Rectangle r = outputPortRect(node);
            if (r.contains(point)) {
                return node;
            }
        }
        return null;
    }

    public NodePort getInputPortAt(Point2D point) {
        for (Node node : getNodesReversed()) {
            for (Port port : node.getInputs()) {
                Rectangle r = inputPortRect(node, port);
                if (r.contains(point)) {
                    return NodePort.of(node, port);
                }
            }
        }
        return null;
    }

    //// Selections ////

    public boolean isRendered(Node node) {
        return getActiveNetwork().getRenderedChild() == node;
    }

    public boolean isSelected(Node node) {
        return (selectedNodes.contains(node.getName()));
    }

    public void select(Node node) {
        selectedNodes.add(node.getName());
    }

    /**
     * Select this node, and only this node.
     * <p/>
     * All other selected nodes will be deselected.
     *
     * @param node The node to select. If node is null, everything is deselected.
     */
    public void singleSelect(Node node) {
        if (selectedNodes.size() == 1 && selectedNodes.contains(node.getName())) return;
        selectedNodes.clear();
        if (node != null && getActiveNetwork().hasChild(node)) {
            selectedNodes.add(node.getName());
            firePropertyChange(SELECT_PROPERTY, null, selectedNodes);
            document.setActiveNode(node);
        }
        repaint();
    }

    public void select(Iterable<Node> nodes) {
        selectedNodes.clear();
        for (Node node : nodes) {
            selectedNodes.add(node.getName());
        }
    }

    //    public void select(Set<NodeView> newSelection) {
//        boolean selectionChanged = false;
//        ArrayList<NodeView> nodeViewsToRemove = new ArrayList<NodeView>();
//        for (NodeView nodeView : selectedNodes) {
//            if (!newSelection.contains(nodeView)) {
//                selectionChanged = true;
//                nodeView.setSelected(false);
//                nodeViewsToRemove.add(nodeView);
//            }
//        }
//        for (NodeView nodeView : nodeViewsToRemove) {
//            selectedNodes.remove(nodeView);
//        }
//        for (NodeView nodeView : newSelection) {
//            if (!selectedNodes.contains(nodeView)) {
//                selectionChanged = true;
//                nodeView.setSelected(true);
//                selectedNodes.add(nodeView);
//            }
//        }
//        if (selectionChanged)
//            firePropertyChange(SELECT_PROPERTY, null, selectedNodes);
//    }
//
    public void toggleSelection(Node node) {
        checkNotNull(node);
        if (selectedNodes.isEmpty()) {
            singleSelect(node);

        } else {
            if (selectedNodes.contains(node.getName())) {
                selectedNodes.remove(node.getName());
            } else {
                selectedNodes.add(node.getName());
            }
            firePropertyChange(SELECT_PROPERTY, null, selectedNodes);
            repaint();
        }
    }

    //
//    public void addToSelection(Set<NodeView> newSelection) {
//        boolean selectionChanged = false;
//        for (NodeView nodeView : newSelection) {
//            if (!selectedNodes.contains(nodeView)) {
//                selectionChanged = true;
//                nodeView.setSelected(true);
//                selectedNodes.add(nodeView);
//            }
//        }
//        if (selectionChanged)
//            firePropertyChange(SELECT_PROPERTY, null, selectedNodes);
//    }
//
//    public void deselect(NodeView nodeView) {
//        if (nodeView == null) return;
//        // If the selectedNodes didn't contain the object in the first place, bail out.
//        // This is to prevent the select event from firing.
//        if (!selectedNodes.contains(nodeView)) return;
//        selectedNodes.remove(nodeView);
//        nodeView.setSelected(false);
//        firePropertyChange(SELECT_PROPERTY, null, selectedNodes);
//    }
//
//    public void selectAll() {
//        boolean selectionChanged = false;
//        for (Object child : getLayer().getChildrenReference()) {
//            if (!(child instanceof NodeView)) continue;
//            NodeView nodeView = (NodeView) child;
//            // Check if the selectedNodes already contained the node view.
//            // If it didn't, that means that the old selectedNodes is different
//            // from the new selectedNodes.
//            if (!selectedNodes.contains(nodeView)) {
//                selectionChanged = true;
//                nodeView.setSelected(true);
//                selectedNodes.add(nodeView);
//            }
//        }
//        if (selectionChanged)
//            firePropertyChange(SELECT_PROPERTY, null, selectedNodes);
//    }
//
    public void deselectAll() {
        if (selectedNodes.isEmpty()) return;
        selectedNodes.clear();
        firePropertyChange(SELECT_PROPERTY, null, selectedNodes);
        document.setActiveNode((Node) null);
        repaint();
    }

    public Iterable<String> getSelectedNodeNames() {
        return selectedNodes;
    }

    public Iterable<Node> getSelectedNodes() {
        ImmutableList.Builder<Node> b = new ImmutableList.Builder<nodebox.node.Node>();
        for (String name : getSelectedNodeNames()) {
            b.add(findNodeWithName(name));
        }
        return b.build();
    }

    public void deleteSelection() {
        document.removeNodes(getSelectedNodes());
    }

    //// Network navigation ////

    private void goUp() {
        JOptionPane.showMessageDialog(this, "Child nodes are not supported yet.");
//        getDocument().goUp();
    }

    private void goDown() {
        JOptionPane.showMessageDialog(this, "Child nodes are not supported yet.");
//        if (selectedNodes.size() != 1) {
//            Toolkit.getDefaultToolkit().beep();
//            return;
//        }
//        NodeView selectedNode = selectedNodes.iterator().next();
//
//        String childPath = Node.path(getDocument().getActiveNetworkPath(), selectedNode.getNodeName());
//        getDocument().setActiveNetwork(childPath);
    }

    //// Input Events ////

    public void keyTyped(KeyEvent e) {
        switch (e.getKeyChar()) {
            case KeyEvent.VK_BACK_SPACE:
                getDocument().deleteSelection();
                break;
            case KeyEvent.VK_U:
                goUp();
                break;
            case KeyEvent.VK_ENTER:
                goDown();
                break;
        }
    }

    public void keyPressed(KeyEvent e) {
        if (e.getKeyCode() == KeyEvent.VK_SHIFT) {
            isShiftPressed = true;
        } else if (e.getKeyCode() == KeyEvent.VK_SPACE) {
            isPanningView = true;
            setCursor(panCursor);
        }
    }

    public void keyReleased(KeyEvent e) {
        if (e.getKeyCode() == KeyEvent.VK_SHIFT) {
            isShiftPressed = false;
        } else if (e.getKeyCode() == KeyEvent.VK_SPACE) {
            isPanningView = false;
            setCursor(defaultCursor);
        }
    }


    public boolean isPanningView() {
        return isPanningView;
    }

    public void mouseClicked(MouseEvent e) {
        Point2D pt = inverseViewTransformPoint(e.getPoint());
        if (e.getButton() == MouseEvent.BUTTON1) {
            if (e.getClickCount() == 1) {
                Node clickedNode = getNodeAt(pt);
                if (clickedNode == null) {
                    deselectAll();
                } else {
                    if (isShiftPressed) {
                        toggleSelection(clickedNode);
                    } else {
                        singleSelect(clickedNode);
                    }
                }
            } else if (e.getClickCount() == 2) {
                Node clickedNode = getNodeAt(pt);
                if (clickedNode == null) {
                    Point gridPoint = pointToGridPoint(e.getPoint());
                    getDocument().showNodeSelectionDialog(gridPoint);
                } else {
                    document.setRenderedNode(clickedNode);
                }
            }
        }
    }

    public void mousePressed(MouseEvent e) {
        if (e.isPopupTrigger()) {
            networkMenu.show(this, e.getX(), e.getY());
        } else {
            Point2D pt = inverseViewTransformPoint(e.getPoint());

            // Check if we're over an output port.
            connectionOutput = getNodeWithOutputPortAt(pt);
            if (connectionOutput != null) return;

            // Check if we're over a connected input port.
            connectionInput = getInputPortAt(pt);
            if (connectionInput != null) {
                // We're over a port, but is it connected?
                Connection c = getActiveNetwork().getConnection(connectionInput.node.getName(), connectionInput.port.getName());
                // Disconnect it, but start a new connection on the same node immediately.
                if (c != null) {
                    getDocument().disconnect(c);
                    connectionOutput = getActiveNetwork().getChild(c.getOutputNode());
                    connectionPoint = pt;
                }
                return;
            }

            // Check if we're pressing a node.
            Node pressedNode = getNodeAt(pt);
            if (pressedNode != null) {
                // Don't immediately set "isDragging."
                // We wait until we actually drag the first time to do the work.
                startDragging = true;
            }
            if (isPanningView) {
                // When panning the view use the original mouse point, not the one affected by the view transform.
                dragStartPoint = e.getPoint();
            }
        }
    }

    public void mouseReleased(MouseEvent e) {
        isDraggingNodes = false;
        if (connectionOutput != null && connectionInput != null) {
            getDocument().connect(connectionOutput, connectionInput.node, connectionInput.port);
        }
        connectionOutput = null;
        if (e.isPopupTrigger()) {
            networkMenu.show(this, e.getX(), e.getY());
        }
        repaint();
    }

    public void mouseEntered(MouseEvent e) {
    }

    public void mouseExited(MouseEvent e) {
    }

    public void mouseDragged(MouseEvent e) {
        Point2D pt = inverseViewTransformPoint(e.getPoint());
        // Panning the view has the first priority.
        if (isPanningView) {
            // When panning the view use the original mouse point, not the one affected by the view transform.
            Point2D offset = minPoint(e.getPoint(), dragStartPoint);
            viewTransform.translate(offset.getX(), offset.getY());
            dragStartPoint = e.getPoint();
            repaint();
            return;
        }

        if (connectionOutput != null) {
            repaint();
            connectionInput = getInputPortAt(pt);
            connectionPoint = pt;
            overInput = getInputPortAt(pt);
        }

        if (startDragging) {
            startDragging = false;
            Node pressedNode = getNodeAt(pt);
            if (pressedNode != null) {
                if (selectedNodes.isEmpty() || !selectedNodes.contains(pressedNode.getName())) {
                    singleSelect(pressedNode);
                }
                isDraggingNodes = true;
                dragPositions = selectedNodePositions();
                dragStartPoint = pt;
            } else {
                isDraggingNodes = false;
            }
        }

        if (isDraggingNodes) {
            Point2D offset = minPoint(pt, dragStartPoint);
            int gridX = (int) Math.round(offset.getX() / GRID_CELL_SIZE);
            int gridY = (int) Math.round(offset.getY() / (float) GRID_CELL_SIZE);
            for (String name : selectedNodes) {
                nodebox.graphics.Point originalPosition = dragPositions.get(name);
                nodebox.graphics.Point newPosition = originalPosition.moved(gridX, gridY);
                getDocument().setNodePosition(findNodeWithName(name), newPosition);
            }
        }
    }

    public void mouseMoved(MouseEvent e) {
        Point2D pt = inverseViewTransformPoint(e.getPoint());
        overOutput = getNodeWithOutputPortAt(pt);
        overInput = getInputPortAt(pt);
        // It is probably very inefficient to repaint the view every time the mouse moves.
        repaint();
    }

    private ImmutableMap<String, nodebox.graphics.Point> selectedNodePositions() {
        ImmutableMap.Builder<String, nodebox.graphics.Point> b = ImmutableMap.builder();
        for (String nodeName : selectedNodes) {
            b.put(nodeName, findNodeWithName(nodeName).getPosition());
        }
        return b.build();
    }

    private Point2D inverseViewTransformPoint(Point p) {
        Point2D pt = new Point2D.Double(p.getX(), p.getY());
        try {
            return viewTransform.inverseTransform(pt, null);
        } catch (NoninvertibleTransformException e) {
            throw new RuntimeException(e);
        }
    }

    private Point2D minPoint(Point2D a, Point2D b) {
        return new Point2D.Double(a.getX() - b.getX(), a.getY() - b.getY());
    }

    //// Inner classes ////

//    private class SelectionMarker extends PNode {
//        public SelectionMarker(Point2D p) {
//            setOffset(p);
//        }
//
//        protected void paint(PPaintContext c) {
//            Graphics2D g = c.getGraphics();
//            g.setColor(Theme.NETWORK_SELECTION_COLOR);
//            PBounds b = getBounds();
//            // Inset the bounds so we don't draw outside the refresh region.
//            b.inset(1, 1);
//            g.fill(b);
//            g.setColor(Theme.NETWORK_SELECTION_BORDER_COLOR);
//            g.draw(b);
//        }
//    }

//    class SelectionHandler extends PBasicInputEventHandler {
//        private Set<NodeView> temporarySelection = new HashSet<NodeView>();
//
//        public void mouseClicked(PInputEvent e) {
//            if (e.getButton() != MouseEvent.BUTTON1) return;
//            deselectAll();
//            getDocument().setActiveNode((Node) null);
//            connectionLayer.mouseClickedEvent(e);
//        }
//
//        public void mousePressed(PInputEvent e) {
//            if (e.getButton() != MouseEvent.BUTTON1) return;
//            temporarySelection.clear();
//            // Make sure no Node View is under the mouse cursor.
//            // In that case, we're not selecting, but moving a node.
//            Point2D p = e.getPosition();
//            NodeView nv = getNodeViewAt(p);
//            if (nv == null) {
//                selectionMarker = new SelectionMarker(p);
//                getLayer().addChild(selectionMarker);
//            } else {
//                selectionMarker = null;
//            }
//        }
//
//        public void mouseDragged(PInputEvent e) {
//            if (selectionMarker == null) return;
//            Point2D prev = selectionMarker.getOffset();
//            Point2D p = e.getPosition();
//            double width = p.getX() - prev.getX();
//            double absWidth = Math.abs(width);
//            double height = p.getY() - prev.getY();
//            double absHeight = Math.abs(height);
//            selectionMarker.setWidth(absWidth);
//            selectionMarker.setHeight(absHeight);
//            selectionMarker.setX(absWidth != width ? width : 0);
//            selectionMarker.setY(absHeight != height ? height : 0);
//            ListIterator childIter = getLayer().getChildrenIterator();
//            connectionLayer.deselect();
//            temporarySelection.clear();
//            while (childIter.hasNext()) {
//                Object o = childIter.next();
//                if (o instanceof NodeView) {
//                    NodeView nodeView = (NodeView) o;
//                    PNode n = (PNode) o;
//                    if (selectionMarker.getFullBounds().intersects(n.getFullBounds())) {
//                        nodeView.setSelected(true);
//                        temporarySelection.add(nodeView);
//                    } else {
//                        nodeView.setSelected(false);
//                    }
//                }
//            }
//        }
//
//        public void mouseReleased(PInputEvent e) {
//            if (selectionMarker == null) return;
//            getLayer().removeChild(selectionMarker);
//            selectionMarker = null;
//            select(temporarySelection);
//            temporarySelection.clear();
//        }
//    }

    private class ResetViewAction extends AbstractAction {
        private ResetViewAction() {
            super("Reset View");
        }

        public void actionPerformed(ActionEvent e) {
            resetViewTransform();
        }
    }

    private class GoUpAction extends AbstractAction {
        private GoUpAction() {
            super("Go Up");
            putValue(Action.ACCELERATOR_KEY, KeyStroke.getKeyStroke(KeyEvent.VK_U, 0));
        }

        public void actionPerformed(ActionEvent e) {
            goUp();
        }
    }

}
