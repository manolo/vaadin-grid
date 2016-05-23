/*
 * Copyright 2000-2014 Vaadin Ltd.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.vaadin.elements.grid.selection;

import com.google.gwt.animation.client.AnimationScheduler;
import com.google.gwt.animation.client.AnimationScheduler.AnimationCallback;
import com.google.gwt.animation.client.AnimationScheduler.AnimationHandle;
import com.google.gwt.core.client.GWT;
import com.google.gwt.dom.client.BrowserEvents;
import com.google.gwt.dom.client.Element;
import com.google.gwt.dom.client.EventTarget;
import com.google.gwt.dom.client.NativeEvent;
import com.google.gwt.dom.client.Node;
import com.google.gwt.dom.client.Style.Visibility;
import com.google.gwt.dom.client.TableCellElement;
import com.google.gwt.dom.client.TableElement;
import com.google.gwt.dom.client.TableSectionElement;
import com.google.gwt.event.dom.client.ClickEvent;
import com.google.gwt.event.dom.client.ClickHandler;
import com.google.gwt.event.dom.client.DomEvent;
import com.google.gwt.event.dom.client.MouseDownEvent;
import com.google.gwt.event.dom.client.MouseDownHandler;
import com.google.gwt.event.dom.client.MouseEvent;
import com.google.gwt.event.dom.client.TouchStartEvent;
import com.google.gwt.event.dom.client.TouchStartHandler;
import com.google.gwt.event.shared.EventHandler;
import com.google.gwt.event.shared.HandlerManager;
import com.google.gwt.event.shared.HandlerRegistration;
import com.google.gwt.query.client.GQuery;
import com.google.gwt.user.client.Event;
import com.google.gwt.user.client.Event.NativePreviewEvent;
import com.google.gwt.user.client.Event.NativePreviewHandler;
import com.google.gwt.user.client.ui.CheckBox;
import com.google.gwt.user.client.ui.Composite;
import com.google.gwt.user.client.ui.Widget;

import com.vaadin.client.WidgetUtil;
import com.vaadin.client.renderers.Renderer;
import com.vaadin.client.widget.escalator.Cell;
import com.vaadin.client.widget.escalator.FlyweightCell;
import com.vaadin.client.widget.escalator.RowContainer;
import com.vaadin.client.widget.grid.CellReference;
import com.vaadin.client.widget.grid.ClickableRenderer.RendererClickHandler;
import com.vaadin.client.widget.grid.EventCellReference;
import com.vaadin.client.widget.grid.RendererCellReference;
import com.vaadin.client.widget.grid.selection.SelectionModel;
import com.vaadin.client.widget.grid.selection.SelectionModel.Multi.Batched;
import com.vaadin.client.widgets.grid.Grid;
import com.vaadin.shared.ui.grid.GridConstants.Section;

import java.util.Collection;
import java.util.HashSet;

/**
 * Renderer showing multi selection check boxes.
 * 
 * @author Vaadin Ltd
 * @param <T>
 *            the type of the associated grid
 * @since 7.4
 */
public class VGridMultiSelectionRenderer<T> implements Renderer<T>{

    private static final String SELECTION_CHECKBOX_CLASSNAME = "-selection-checkbox";

    /** The size of the autoscroll area, both top and bottom. */
    private static final int SCROLL_AREA_GRADIENT_PX = 100;

    /** The maximum number of pixels per second to autoscroll. */
    private static final int SCROLL_TOP_SPEED_PX_SEC = 500;

    /**
     * The minimum area where the grid doesn't scroll while the pointer is
     * pressed.
     */
    private static final int MIN_NO_AUTOSCROLL_AREA_PX = 50;

    /**
     * Handler for MouseDown and TouchStart events for selection checkboxes.
     * 
     * @since 7.5
     */
    private final class CheckBoxEventHandler implements MouseDownHandler,
            TouchStartHandler, ClickHandler {
        private final CheckBox checkBox;

        /**
         * @param checkBox
         *            checkbox widget for this handler
         */
        private CheckBoxEventHandler(CheckBox checkBox) {
            this.checkBox = checkBox;
        }

        @Override
        public void onMouseDown(MouseDownEvent event) {
            if (event.getNativeButton() == NativeEvent.BUTTON_LEFT) {
                startDragSelect(event.getNativeEvent(), checkBox.getElement());
            }
        }

        @Override
        public void onTouchStart(TouchStartEvent event) {
            startDragSelect(event.getNativeEvent(), checkBox.getElement());
        }

        @Override
        public void onClick(ClickEvent event) {
            // Clicking is already handled with MultiSelectionRenderer
            event.preventDefault();
            event.stopPropagation();
        }
    }

    /**
     * This class's main objective is to listen when to stop autoscrolling, and
     * make sure everything stops accordingly.
     */
    private class TouchEventHandler implements NativePreviewHandler {
        @Override
        public void onPreviewNativeEvent(final NativePreviewEvent event) {
            switch (event.getTypeInt()) {
            case Event.ONTOUCHSTART: {
                if (event.getNativeEvent().getTouches().length() == 1) {
                    /*
                     * Something has dropped a touchend/touchcancel and the
                     * scroller is most probably running amok. Let's cancel it
                     * and pretend that everything's going as expected
                     * 
                     * Because this is a preview, this code is run before the
                     * event handler in MultiSelectionRenderer.onBrowserEvent.
                     * Therefore, we can simply kill everything and let that
                     * method restart things as they should.
                     */
                    autoScrollHandler.stop();

                    /*
                     * Related TODO: investigate why iOS seems to ignore a
                     * touchend/touchcancel when frames are dropped, and/or if
                     * something can be done about that.
                     */
                }
                break;
            }

            case Event.ONTOUCHMOVE:
                event.cancel();
                break;

            case Event.ONTOUCHEND:
            case Event.ONTOUCHCANCEL:
                /*
                 * Remember: targetElement is always where touchstart started,
                 * not where the finger is pointing currently.
                 */
                final Element targetElement = Element.as(event.getNativeEvent()
                        .getEventTarget());
                if (isInFirstColumn(targetElement)) {
                    removeNativeHandler();
                    event.cancel();
                }
                break;
            }
        }

        private boolean isInFirstColumn(final Element element) {
            if (element == null) {
                return false;
            }
            final Element tbody = getTbodyElement();

            if (tbody == null || !tbody.isOrHasChild(element)) {
                return false;
            }

            /*
             * The null-parent in the while clause is in the case where element
             * is an immediate tr child in the tbody. Should never happen in
             * internal code, but hey...
             */
            Element cursor = element;
            while (cursor.getParentElement() != null
                    && cursor.getParentElement().getParentElement() != tbody) {
                cursor = cursor.getParentElement();
            }

            final Element tr = cursor.getParentElement();
            return tr.getFirstChildElement().equals(cursor);
        }
    }

    /**
     * This class's responsibility is to
     * <ul>
     * <li>scroll the table while a pointer is kept in a scrolling zone and
     * <li>select rows whenever a pointer is "activated" on a selection cell
     * </ul>
     * <p>
     * <em>Techical note:</em> This class is an AnimationCallback because we
     * need a timer: when the finger is kept in place while the grid scrolls, we
     * still need to be able to make new selections. So, instead of relying on
     * events (which won't be fired, since the pointer isn't necessarily
     * moving), we do this check on each frame while the pointer is "active"
     * (mouse is pressed, finger is on screen).
     */
    private class AutoScrollerAndSelector implements AnimationCallback {

        /**
         * If the acceleration gradient area is smaller than this, autoscrolling
         * will be disabled (it becomes too quick to accelerate to be usable).
         */
        private static final int GRADIENT_MIN_THRESHOLD_PX = 10;

        /**
         * The speed at which the gradient area recovers, once scrolling in that
         * direction has started.
         */
        private static final int SCROLL_AREA_REBOUND_PX_PER_SEC = 1;
        private static final double SCROLL_AREA_REBOUND_PX_PER_MS = SCROLL_AREA_REBOUND_PX_PER_SEC / 1000.0d;

        /**
         * The lowest y-coordinate on the {@link Event#getClientY() client} from
         * where we need to start scrolling towards the top.
         */
        private int topBound = -1;

        /**
         * The highest y-coordinate on the {@link Event#getClientY() client}
         * from where we need to scrolling towards the bottom.
         */
        private int bottomBound = -1;

        /**
         * <code>true</code> if the pointer is selecting, <code>false</code> if
         * the pointer is deselecting.
         */
        private final boolean selectionPaint;

        /**
         * The area where the selection acceleration takes place. If &lt;
         * {@link #GRADIENT_MIN_THRESHOLD_PX}, autoscrolling is disabled
         */
        private final int gradientArea;

        /**
         * The number of pixels per seconds we currently are scrolling (negative
         * is towards the top, positive is towards the bottom).
         */
        private double scrollSpeed = 0;

        private double prevTimestamp = 0;

        /**
         * This field stores fractions of pixels to scroll, to make sure that
         * we're able to scroll less than one px per frame.
         */
        private double pixelsToScroll = 0.0d;

        /** Should this animator be running. */
        private boolean running = false;

        /** The handle in which this instance is running. */
        private AnimationHandle handle;

        /** The pointer's pageX coordinate of the first click. */
        private int initialPageX = -1;

        /** The pointer's pageY coordinate. */
        private int pageY;

        /** The logical index of the row that was most recently modified. */
        private int lastModifiedLogicalRow = -1;

        /** @see #doScrollAreaChecks(int) */
        private int finalTopBound;

        /** @see #doScrollAreaChecks(int) */
        private int finalBottomBound;

        private boolean scrollAreaShouldRebound = false;

        private final int bodyAbsoluteTop;
        private final int bodyAbsoluteBottom;

        public AutoScrollerAndSelector(final int topBound,
                final int bottomBound, final int gradientArea,
                final boolean selectionPaint) {
            finalTopBound = topBound;
            finalBottomBound = bottomBound;
            this.gradientArea = gradientArea;
            this.selectionPaint = selectionPaint;

            bodyAbsoluteTop = getBodyClientTop();
            bodyAbsoluteBottom = getBodyClientBottom();
        }

        @Override
        public void execute(final double timestamp) {
            final double timeDiff = timestamp - prevTimestamp;
            prevTimestamp = timestamp;

            reboundScrollArea(timeDiff);

            pixelsToScroll += scrollSpeed * (timeDiff / 1000.0d);
            final int intPixelsToScroll = (int) pixelsToScroll;
            pixelsToScroll -= intPixelsToScroll;

            if (intPixelsToScroll != 0) {
                grid.setScrollTop(grid.getScrollTop() + intPixelsToScroll);
            }

            int constrainedPageY = Math.max(bodyAbsoluteTop,
                    Math.min(bodyAbsoluteBottom, pageY));
            int logicalRow = getLogicalRowIndex(WidgetUtil.getElementFromPoint(
                    initialPageX, constrainedPageY));

            int incrementOrDecrement = (logicalRow > lastModifiedLogicalRow) ? 1
                    : -1;

            /*
             * Both pageY and initialPageX have their initialized (and
             * unupdated) values while the cursor hasn't moved since the first
             * invocation. This will lead to logicalRow being -1, until the
             * pointer has been moved.
             */
            while (logicalRow != -1 && lastModifiedLogicalRow != logicalRow) {
                lastModifiedLogicalRow += incrementOrDecrement;
                setSelected(lastModifiedLogicalRow, selectionPaint);
            }

            reschedule();
        }

        /**
         * If the scroll are has been offset by the pointer starting out there,
         * move it back a bit
         */
        private void reboundScrollArea(double timeDiff) {
            if (!scrollAreaShouldRebound) {
                return;
            }

            int reboundPx = (int) Math.ceil(SCROLL_AREA_REBOUND_PX_PER_MS
                    * timeDiff);
            if (topBound < finalTopBound) {
                topBound += reboundPx;
                topBound = Math.min(topBound, finalTopBound);
                updateScrollSpeed(pageY);
            } else if (bottomBound > finalBottomBound) {
                bottomBound -= reboundPx;
                bottomBound = Math.max(bottomBound, finalBottomBound);
                updateScrollSpeed(pageY);
            }
        }

        private void updateScrollSpeed(final int pointerPageY) {

            final double ratio;
            if (pointerPageY < topBound) {
                final double distance = pointerPageY - topBound;
                ratio = Math.max(-1, distance / gradientArea);
            }

            else if (pointerPageY > bottomBound) {
                final double distance = pointerPageY - bottomBound;
                ratio = Math.min(1, distance / gradientArea);
            }

            else {
                ratio = 0;
            }

            scrollSpeed = ratio * SCROLL_TOP_SPEED_PX_SEC;
        }

        public void start(int logicalRowIndex) {
            running = true;
            setSelected(logicalRowIndex, selectionPaint);
            lastModifiedLogicalRow = logicalRowIndex;
            reschedule();
        }

        public void stop() {
            running = false;

            if (handle != null) {
                handle.cancel();
                handle = null;
            }
        }

        private void reschedule() {
            if (running && gradientArea >= GRADIENT_MIN_THRESHOLD_PX) {
                handle = AnimationScheduler.get().requestAnimationFrame(this,
                        grid.getElement());
            }
        }

        public void updatePointerCoords(int pageX, int pageY) {
            doScrollAreaChecks(pageY);
            updateScrollSpeed(pageY);
            this.pageY = pageY;

            if (initialPageX == -1) {
                initialPageX = pageX;
            }
        }

        /**
         * This method checks whether the first pointer event started in an area
         * that would start scrolling immediately, and does some actions
         * accordingly.
         * <p>
         * If it is, that scroll area will be offset "beyond" the pointer (above
         * if pointer is towards the top, otherwise below).
         * <p>
         * <span style="font-size:smaller">*) This behavior will change in
         * future patches (henrik paul 2.7.2014)</span>
         */
        private void doScrollAreaChecks(int pageY) {
            /*
             * The first run makes sure that neither scroll position is
             * underneath the finger, but offset to either direction from
             * underneath the pointer.
             */
            if (topBound == -1) {
                topBound = Math.min(finalTopBound, pageY);
                bottomBound = Math.max(finalBottomBound, pageY);
            }

            /*
             * Subsequent runs make sure that the scroll area grows (but doesn't
             * shrink) with the finger, but no further than the final bound.
             */
            else {
                int oldTopBound = topBound;
                if (topBound < finalTopBound) {
                    topBound = Math.max(topBound,
                            Math.min(finalTopBound, pageY));
                }

                int oldBottomBound = bottomBound;
                if (bottomBound > finalBottomBound) {
                    bottomBound = Math.min(bottomBound,
                            Math.max(finalBottomBound, pageY));
                }

                final boolean topDidNotMove = oldTopBound == topBound;
                final boolean bottomDidNotMove = oldBottomBound == bottomBound;
                final boolean wasVerticalMovement = pageY != this.pageY;
                scrollAreaShouldRebound = (topDidNotMove && bottomDidNotMove && wasVerticalMovement);
            }
        }
    }

    /**
     * This class makes sure that pointer movemenets are registered and
     * delegated to the autoscroller so that it can:
     * <ul>
     * <li>modify the speed in which we autoscroll.
     * <li>"paint" a new row with the selection.
     * </ul>
     * Essentially, when a pointer is pressed on the selection column, a native
     * preview handler is registered (so that selection gestures can happen
     * outside of the selection column). The handler itself makes sure that it's
     * detached when the pointer is "lifted".
     */
    private class AutoScrollHandler {
        private AutoScrollerAndSelector autoScroller;

        /** The registration info for {@link #scrollPreviewHandler} */
        private HandlerRegistration handlerRegistration;

        private final NativePreviewHandler scrollPreviewHandler = new NativePreviewHandler() {
            @Override
            public void onPreviewNativeEvent(final NativePreviewEvent event) {
                if (autoScroller == null) {
                    stop();
                    return;
                }

                final NativeEvent nativeEvent = event.getNativeEvent();
                int pageY = 0;
                int pageX = 0;
                switch (event.getTypeInt()) {
                case Event.ONMOUSEMOVE:
                case Event.ONTOUCHMOVE:
                    pageY = WidgetUtil.getTouchOrMouseClientY(nativeEvent);
                    pageX = WidgetUtil.getTouchOrMouseClientX(nativeEvent);
                    autoScroller.updatePointerCoords(pageX, pageY);
                    break;
                case Event.ONMOUSEUP:
                case Event.ONTOUCHEND:
                case Event.ONTOUCHCANCEL:
                    stop();
                    break;
                }
            }
        };

        /**
         * The top bound, as calculated from the {@link Event#getClientY()
         * client} coordinates.
         */
        private int topBound = -1;

        /**
         * The bottom bound, as calculated from the {@link Event#getClientY()
         * client} coordinates.
         */
        private int bottomBound = -1;

        /** The size of the autoscroll acceleration area. */
        private int gradientArea;

        public void start(int logicalRowIndex) {

            SelectionModel<T> model = grid.getSelectionModel();
            if (model instanceof Batched) {
                Batched<?> batchedModel = (Batched<?>) model;
                batchedModel.startBatchSelect();
            }

            /*
             * bounds are updated whenever the autoscroll cycle starts, to make
             * sure that the widget hasn't changed in size, moved around, or
             * whatnot.
             */
            updateScrollBounds();

            assert handlerRegistration == null : "handlerRegistration was not null";
            assert autoScroller == null : "autoScroller was not null";
            handlerRegistration = Event
                    .addNativePreviewHandler(scrollPreviewHandler);

            autoScroller = new AutoScrollerAndSelector(topBound, bottomBound,
                    gradientArea, !isSelected(logicalRowIndex));
            autoScroller.start(logicalRowIndex);
        }

        private void updateScrollBounds() {
            final int topBorder = getBodyClientTop();
            final int bottomBorder = getBodyClientBottom();

            topBound = topBorder + SCROLL_AREA_GRADIENT_PX;
            bottomBound = bottomBorder - SCROLL_AREA_GRADIENT_PX;
            gradientArea = SCROLL_AREA_GRADIENT_PX;

            // modify bounds if they're too tightly packed
            if (bottomBound - topBound < MIN_NO_AUTOSCROLL_AREA_PX) {
                int adjustment = MIN_NO_AUTOSCROLL_AREA_PX
                        - (bottomBound - topBound);
                topBound -= adjustment / 2;
                bottomBound += adjustment / 2;
                gradientArea -= adjustment / 2;
            }
        }

        public void stop() {
            if (handlerRegistration != null) {
                handlerRegistration.removeHandler();
                handlerRegistration = null;
            }

            if (autoScroller != null) {
                autoScroller.stop();
                autoScroller = null;
            }

            SelectionModel<T> model = grid.getSelectionModel();
            if (model instanceof Batched) {
                Batched<?> batchedModel = (Batched<?>) model;
                batchedModel.commitBatchSelect();
            }

            removeNativeHandler();
        }
    }

    private static final String LOGICAL_ROW_PROPERTY_INT = "vEscalatorLogicalRow";

    private final Grid<T> grid;
    private HandlerRegistration nativePreviewHandlerRegistration;

    private final AutoScrollHandler autoScrollHandler = new AutoScrollHandler();

    public VGridMultiSelectionRenderer(final Grid grid) {
        this.grid = grid;
    }

    public void destroy() {
        if (nativePreviewHandlerRegistration != null) {
            removeNativeHandler();
        }
    }

    public CheckBox createWidget() {
        final CheckBox checkBox = GWT.create(CheckBox.class);
        checkBox.setStylePrimaryName(grid.getStylePrimaryName()
                + SELECTION_CHECKBOX_CLASSNAME);
        CheckBoxEventHandler handler = new CheckBoxEventHandler(checkBox);

        // Sink events
        checkBox.sinkBitlessEvent(BrowserEvents.MOUSEDOWN);
        checkBox.sinkBitlessEvent(BrowserEvents.TOUCHSTART);
        checkBox.sinkBitlessEvent(BrowserEvents.CLICK);

        // Add handlers
        checkBox.addMouseDownHandler(handler);
        checkBox.addTouchStartHandler(handler);
        checkBox.addClickHandler(handler);
        
        checkBox.setTabIndex(-1);
        checkBox.addStyleName("vaadin-grid style-scope");
        GQuery.$(checkBox).children()
                .addClass("vaadin-grid", "style-scope");

        return checkBox;
    }

    public void render(final RendererCellReference cell, final Boolean data,
            CheckBox checkBox) {
        checkBox.setValue(data, false);
        checkBox.setEnabled(!grid.isEditorActive());
        checkBox.getElement().setPropertyInt(LOGICAL_ROW_PROPERTY_INT,
                cell.getRowIndex());
    }

    public Collection<String> getConsumedEvents() {
        final HashSet<String> events = new HashSet<String>();

        /*
         * this column's first interest is only to attach a NativePreventHandler
         * that does all the magic. These events are the beginning of that
         * cycle.
         */
        events.add(BrowserEvents.MOUSEDOWN);
        events.add(BrowserEvents.TOUCHSTART);

        return events;
    }

    public boolean onBrowserEvent(final CellReference<?> cell,
            final NativeEvent event) {
        if (BrowserEvents.TOUCHSTART.equals(event.getType())
                || (BrowserEvents.MOUSEDOWN.equals(event.getType()) && event
                        .getButton() == NativeEvent.BUTTON_LEFT)) {
            startDragSelect(event, Element.as(event.getEventTarget()));
            return true;
        } else {
            throw new IllegalStateException("received unexpected event: "
                    + event.getType());
        }
    }

    private void startDragSelect(NativeEvent event, final Element target) {
        injectNativeHandler();
        int logicalRowIndex = getLogicalRowIndex(target);
        autoScrollHandler.start(logicalRowIndex);
        event.preventDefault();
        event.stopPropagation();
    }

    private void injectNativeHandler() {
        removeNativeHandler();
        nativePreviewHandlerRegistration = Event
                .addNativePreviewHandler(new TouchEventHandler());
    }

    private void removeNativeHandler() {
        if (nativePreviewHandlerRegistration != null) {
            nativePreviewHandlerRegistration.removeHandler();
            nativePreviewHandlerRegistration = null;
        }
    }

    private int getLogicalRowIndex(final Element target) {
        if (target == null) {
            return -1;
        }

        /*
         * We can't simply go backwards until we find a <tr> first element,
         * because of the table-in-table scenario. We need to, unfortunately, go
         * up from our known root.
         */
        final Element tbody = getTbodyElement();
        Element tr = tbody.getFirstChildElement();
        while (tr != null) {
            if (tr.isOrHasChild(target)) {
                final Element td = tr.getFirstChildElement();
                assert td != null : "Cell has disappeared";

                final Element checkbox = td.getFirstChildElement();
                assert checkbox != null : "Checkbox has disappeared";

                return checkbox.getPropertyInt(LOGICAL_ROW_PROPERTY_INT);
            }
            tr = tr.getNextSiblingElement();
        }
        return -1;
    }

    private TableElement getTableElement() {
        final Element root = grid.getElement();
        final Element tablewrapper = Element.as(root.getChild(2));
        if (tablewrapper != null) {
            return TableElement.as(tablewrapper.getFirstChildElement());
        } else {
            return null;
        }
    }

    private TableSectionElement getTbodyElement() {
        TableElement table = getTableElement();
        if (table != null) {
            return table.getTBodies().getItem(0);
        } else {
            return null;
        }
    }

    private TableSectionElement getTheadElement() {
        TableElement table = getTableElement();
        if (table != null) {
            return table.getTHead();
        } else {
            return null;
        }
    }

    private TableSectionElement getTfootElement() {
        TableElement table = getTableElement();
        if (table != null) {
            return table.getTFoot();
        } else {
            return null;
        }
    }

    /** Get the "top" of an element in relation to "client" coordinates. */
    private int getClientTop(final Element e) {
        return e.getAbsoluteTop();
    }

    private int getBodyClientBottom() {
        return getClientTop(getTfootElement()) - 1;
    }

    private int getBodyClientTop() {
        // Off by one pixel miscalculation. possibly border related.
        return getClientTop(grid.getElement())
                + getTheadElement().getOffsetHeight() + 1;
    }

    protected boolean isSelected(final int logicalRow) {
        return grid.isSelected(grid.getDataSource().getRow(logicalRow));
    }

    protected void setSelected(final int logicalRow, final boolean select) {
        T row = grid.getDataSource().getRow(logicalRow);
        if (select) {
            grid.select(row);
        } else {
            grid.deselect(row);
        }
    }
    

    /**
     * A handler for {@link RendererClickEvent renderer click events}.
     * 
     * @param <R>
     *            the row type of the containing Grid
     * 
     * @see {@link ButtonRenderer#addClickHandler(RendererClickHandler)}
     */
    public interface RendererClickHandler<R> extends EventHandler {

        /**
         * Called when a rendered button is clicked.
         * 
         * @param event
         *            the event representing the click
         */
        void onClick(RendererClickEvent<R> event);
    }

    /**
     * An event fired when a widget rendered by a ClickableWidgetRenderer
     * subclass is clicked.
     * 
     * @param <R>
     *            the row type of the containing Grid
     */
    @SuppressWarnings("rawtypes")
    public static class RendererClickEvent<R> extends
            MouseEvent<RendererClickHandler> {

        @SuppressWarnings("unchecked")
        static final Type<RendererClickHandler> TYPE = new Type<RendererClickHandler>(
                BrowserEvents.CLICK, new RendererClickEvent());

        private CellReference<R> cell;

        private R row;

        private RendererClickEvent() {
        }

        /**
         * Returns the cell of the clicked button.
         * 
         * @return the cell
         */
        public CellReference<R> getCell() {
            return cell;
        }

        /**
         * Returns the data object corresponding to the row of the clicked
         * button.
         * 
         * @return the row data object
         */
        public R getRow() {
            return row;
        }

        @Override
        public Type<RendererClickHandler> getAssociatedType() {
            return TYPE;
        }

        @Override
        @SuppressWarnings("unchecked")
        protected void dispatch(RendererClickHandler handler) {

            EventTarget target = getNativeEvent().getEventTarget();

            if (!Element.is(target)) {
                return;
            }

            Element e = Element.as(target);
            Grid<R> grid = (Grid<R>) findClosestParentGrid(e);

            cell = findCell(grid, e);
            row = cell.getRow();

            handler.onClick(this);
        }

        /**
         * Returns the cell the given element belongs to.
         * 
         * @param grid
         *            the grid instance that is queried
         * @param e
         *            a cell element or the descendant of one
         * @return the cell or null if the element is not a grid cell or a
         *         descendant of one
         */
        private static <T> CellReference<T> findCell(Grid<T> grid, Element e) {
            RowContainer container = grid.getEscalator().findRowContainer(e);
            if (container == null) {
                return null;
            }
            Cell cell = container.getCell(e);
            EventCellReference<T> cellReference = new EventCellReference<T>(
                    grid);
            // FIXME: Section is currently always body. Might be useful for the
            // future to have an actual check.
            cellReference.set(cell, Section.BODY);
            return cellReference;
        }

        /**
         * Returns the Grid instance containing the given element, if any.
         * <p>
         * <strong>Note:</strong> This method may not work reliably if the grid
         * in question is wrapped in a {@link Composite} <em>unless</em> the
         * element is inside another widget that is a child of the wrapped grid;
         * please refer to the note in
         * {@link WidgetUtil#findWidget(Element, Class) Util.findWidget} for
         * details.
         * 
         * @param e
         *            the element whose parent grid to find
         * @return the parent grid or null if none found.
         */
        private static Grid<?> findClosestParentGrid(Element e) {
            Widget w = WidgetUtil.findWidget(e, null);

            while (w != null && !(w instanceof Grid)) {
                w = w.getParent();
            }
            return (Grid<?>) w;
        }
    }

    private HandlerManager handlerManager;

    /**
     * Adds a click handler to this button renderer. The handler is invoked
     * every time one of the widgets rendered by this renderer is clicked.
     * <p>
     * Note that the row type of the click handler must match the row type of
     * the containing Grid.
     * 
     * @param handler
     *            the click handler to be added
     */
    public HandlerRegistration addClickHandler(RendererClickHandler<?> handler) {
        if (handlerManager == null) {
            handlerManager = new HandlerManager(this);
        }
        return handlerManager.addHandler(RendererClickEvent.TYPE, handler);
    }

    public void onClick(ClickEvent event) {
        /*
         * The handler manager is lazily instantiated so it's null iff
         * addClickHandler is never called.
         */
        if (handlerManager != null) {
            DomEvent.fireNativeEvent(event.getNativeEvent(), handlerManager);
        }
    }
    
    

    @Override
    public void render(RendererCellReference cell, T data) {
        render(cell, (Boolean)data, (CheckBox)getWidget(cell.getElement(), null));
    }

    /**
     * Returns the widget contained inside the given cell element, or null if it
     * is not an instance of the given class. Cannot be called for cells that do
     * not contain a widget.
     * 
     * @param e
     *            the element inside to find a widget
     * @param klass
     *            the type of the widget to find
     * @return the widget inside the element, or null if its type does not match
     */
    protected static <W extends Widget> W getWidget(TableCellElement e,
            Class<W> klass) {
        W w = WidgetUtil.findWidget(e.getFirstChildElement(), klass);
        assert w == null || w.getElement() == e.getFirstChildElement() : "Widget not found inside cell";
        return w;
    }
    

    /**
     * Called after the cell is deemed to be destroyed and no longer used by the
     * Grid. Called after the cell element is detached from the DOM.
     * <p>
     * The row object in the cell reference will be <code>null</code> since the
     * row might no longer be present in the data source.
     * 
     * @param cell
     *            The cell. Note that the cell is not to be stored outside of
     *            the method as the cell instance will change. See
     *            {@link FlyweightCell}
     */
    public void destroy(RendererCellReference cell) {
        // Implement if needed
    }

    /**
     * Used by Grid to toggle whether to show actual data or just an empty
     * placeholder while data is loading. This method is invoked whenever a cell
     * changes between data being available and data missing.
     * <p>
     * Default implementation hides content by setting visibility: hidden to all
     * elements inside the cell. Text nodes are left as is - renderers that add
     * such to the root element need to implement explicit support hiding them.
     * 
     * @param cell
     *            The cell
     * @param hasData
     *            Has the cell content been loaded from the data source
     * 
     */
    public void setContentVisible(RendererCellReference cell, boolean hasData) {
        Element cellElement = cell.getElement();
        for (int n = 0; n < cellElement.getChildCount(); n++) {
            Node node = cellElement.getChild(n);
            if (Element.is(node)) {
                Element e = Element.as(node);
                if (hasData) {
                    e.getStyle().clearVisibility();
                } else {
                    e.getStyle().setVisibility(Visibility.HIDDEN);
                }
            }
        }
    }

    /**
     * Called when the cell is activated by pressing <code>enter</code>, double
     * clicking or performing a double tap on the cell.
     * 
     * @param cell
     *            the activated cell
     * @return <code>true</code> if event was handled and should not be
     *         interpreted as a generic gesture by Grid.
     */
    public boolean onActivate(CellReference<?> cell) {
        return false;
    }
}
