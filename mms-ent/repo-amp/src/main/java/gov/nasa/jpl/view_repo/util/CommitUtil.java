package gov.nasa.jpl.view_repo.util;

import java.io.IOException;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import gov.nasa.jpl.view_repo.webscripts.util.SitePermission;
import org.alfresco.service.ServiceRegistry;
import org.alfresco.service.cmr.security.PermissionService;
import org.alfresco.service.cmr.site.SiteInfo;
import org.apache.commons.compress.archivers.dump.DumpArchiveEntry;
import org.apache.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import gov.nasa.jpl.mbee.util.Pair;
import gov.nasa.jpl.mbee.util.TimeUtils;
import gov.nasa.jpl.view_repo.connections.JmsConnection;
import gov.nasa.jpl.view_repo.db.ElasticHelper;
import gov.nasa.jpl.view_repo.db.ElasticResult;
import gov.nasa.jpl.view_repo.db.Node;
import gov.nasa.jpl.view_repo.db.PostgresHelper;
import gov.nasa.jpl.view_repo.db.PostgresHelper.DbCommitTypes;
import gov.nasa.jpl.view_repo.db.PostgresHelper.DbEdgeTypes;
import gov.nasa.jpl.view_repo.db.PostgresHelper.DbNodeTypes;

import javax.servlet.http.HttpServletResponse;
import javax.xml.ws.Service;

/**
 * Utilities for saving commits and sending out deltas based on commits
 *
 * @author cinyoung
 */
public class CommitUtil {
    static Logger logger = Logger.getLogger(CommitUtil.class);

    public static final String TYPE_BRANCH = "BRANCH";
    public static final String TYPE_COMMIT = "COMMIT";
    public static final String TYPE_DELTA = "DELTA";
    public static final String TYPE_MERGE = "MERGE";
    public static boolean cleanJson = false;
    private static ElasticHelper eh = null;

    private CommitUtil() {
        // try {
        // eh = new ElasticHelper();
        // } catch (Exception e) {
        // logger.error(String.format("%s", LogUtil.getStackTrace(e)));
        // }
    }

    private static JmsConnection jmsConnection = null;

    public static void setJmsConnection(JmsConnection jmsConnection) {
        if (logger.isInfoEnabled())
            logger.info("Setting jms");
        CommitUtil.jmsConnection = jmsConnection;
    }


    /**
     * Given a workspace gets an ordered list of the commit history
     *
     * @param workspace
     * @param services
     * @param response
     * @return
     */
    public static ArrayList<EmsScriptNode> getCommits(WorkspaceNode workspace, ServiceRegistry services,
        StringBuffer response) {
        // TODO: reimplement - need to filter commits by IDs... going to be tough with shared server
        return null;
    }


    /**
     * Get the most recent commit in a workspace
     *
     * @param ws
     * @param services
     * @param response
     * @return
     */
    public static EmsScriptNode getLastCommit(WorkspaceNode ws, ServiceRegistry services, StringBuffer response) {
        // TODO: reimplement
        return null;
    }

    /**
     * Return the latest commit before or equal to the passed date
     */
    public static EmsScriptNode getLatestCommitAtTime(Date date, WorkspaceNode workspace, ServiceRegistry services,
        StringBuffer response) {
        // TODO: reimplement
        return null;
    }


    /**
     */
    public static boolean revertCommit(EmsScriptNode commit, ServiceRegistry services) {
        // TODO: implement
        return true;
    }


    public static DbNodeTypes getNodeType(JSONObject e) {

        String type = e.optString(Sjm.TYPE).toLowerCase();

        if ((type.contains("class") || type.contains("generalization") || type.contains("dependency")) && e
            .has(Sjm.APPLIEDSTEREOTYPEIDS)) {
            JSONArray typeArray = e.optJSONArray(Sjm.APPLIEDSTEREOTYPEIDS);

            for (int i = 0; i < typeArray.length(); i++) {
                String typeString = typeArray.getString(i);
                if (Sjm.STEREOTYPEIDS.containsKey(typeString)) {
                    if (!type.equals("document")) {
                        type = Sjm.STEREOTYPEIDS.get(typeString);
                    }
                }
            }
        }

        switch (type) {
            case "site":
                return DbNodeTypes.SITE;
            case "project":
                return DbNodeTypes.PROJECT;
            case "package":
                if (isSite(e)) {
                    return DbNodeTypes.SITEANDPACKAGE;
                } else {
                    return DbNodeTypes.PACKAGE;
                }
            case "document":
                return DbNodeTypes.DOCUMENT;
            case "comment":
                return DbNodeTypes.COMMENT;
            case "constraint":
                return DbNodeTypes.CONSTRAINT;
            case "instancespecification":
                return DbNodeTypes.INSTANCESPECIFICATION;
            case "operation":
                return DbNodeTypes.OPERATION;
            case "property":
                return DbNodeTypes.PROPERTY;
            case "parameter":
                return DbNodeTypes.PARAMETER;
            case "view":
                return DbNodeTypes.VIEW;
            case "viewpoint":
                return DbNodeTypes.VIEWPOINT;
            case "mount":
                return DbNodeTypes.MOUNT;
            default:
                return DbNodeTypes.ELEMENT;
        }
    }

    public static boolean isSite(JSONObject element) {
        return element.has(Sjm.ISSITE) && element.getBoolean(Sjm.ISSITE);
    }

    private static boolean bulkElasticEntry(JSONArray elements, String operation, boolean refresh) {
        if (elements.length() > 0) {
            try {
                boolean bulkEntry = eh.bulkIndexElements(elements, operation, refresh);
                if (!bulkEntry) {
                    return false;
                }
            } catch (IOException e) {
                logger.warn(String.format("%s", LogUtil.getStackTrace(e)));
                return false;
            }
        }

        return true;
    }

    private static boolean isPartProperty(JSONObject e) {
        if (!e.has(Sjm.TYPE) || !e.getString(Sjm.TYPE).equals("Property")) {
            return false;
        }
        JSONArray appliedS = e.optJSONArray(Sjm.APPLIEDSTEREOTYPEIDS);
        if (appliedS == null || appliedS.length() == 0) {
            return false;
        }
        for (int i = 0; i < appliedS.length(); i++) {
            if (appliedS.getString(i).equals("_15_0_be00301_1199377756297_348405_2678")) {
                return true;
            }
        }
        return false;
    }

    private static boolean processDeltasForDb(JSONObject delta, String projectId,
        String refId, JSONObject jmsPayload, boolean withChildViews, ServiceRegistry services) {
        // :TODO write to elastic for elements, write to postgres, write to elastic for commits
        // :TODO should return a 500 here to stop writes if one insert fails
        PostgresHelper pgh = new PostgresHelper();
        pgh.setProject(projectId);
        pgh.setWorkspace(refId);

        //Boolean initialCommit = pgh.isInitialCommit();

        JSONArray added = delta.optJSONArray("addedElements");
        JSONArray updated = delta.optJSONArray("updatedElements");
        JSONArray deleted = delta.optJSONArray("deletedElements");

        String creator = delta.getJSONObject("commit").getString(Sjm.CREATOR);
        String commitElasticId = delta.getJSONObject("commit").getString(Sjm.ELASTICID);

        JSONObject jmsWorkspace = new JSONObject();
        JSONArray jmsAdded = new JSONArray();
        JSONArray jmsUpdated = new JSONArray();
        JSONArray jmsDeleted = new JSONArray();

        List<String> deletedSysmlIds = new ArrayList<>();
        List<Pair<String, String>> addEdges = new ArrayList<>();
        List<Pair<String, String>> viewEdges = new ArrayList<>();
        List<Pair<String, String>> childViewEdges = new ArrayList<>();

        if (bulkElasticEntry(added, "added", withChildViews) && bulkElasticEntry(updated, "updated", withChildViews)) {

            try {
                List<Pair<String, String>> plist = new LinkedList<>();

                List<Map<String, String>> nodeInserts = new ArrayList<>();
                List<Map<String, String>> edgeInserts = new ArrayList<>();
                List<Map<String, String>> childEdgeInserts = new ArrayList<>();
                List<Map<String, String>> nodeUpdates = new ArrayList<>();
                Set<String> uniqueEdge = new HashSet<>();

                for (int i = 0; i < added.length(); i++) {
                    JSONObject e = added.getJSONObject(i);
                    Map<String, String> node = new HashMap<>();
                    jmsAdded.put(e.getString(Sjm.SYSMLID));
                    int nodeType = getNodeType(e).getValue();

                    if (e.has(Sjm.ELASTICID)) {
                        node.put(Sjm.ELASTICID, e.getString(Sjm.ELASTICID));
                        node.put(Sjm.SYSMLID, e.getString(Sjm.SYSMLID));
                        node.put("nodetype", Integer.toString(nodeType));
                        node.put("initialcommit", e.getString(Sjm.ELASTICID));
                        node.put("lastcommit", commitElasticId);
                        nodeInserts.add(node);
                    }
                    if (e.has(Sjm.OWNERID) && e.getString(Sjm.OWNERID) != null && e.getString(Sjm.SYSMLID) != null) {
                        Pair<String, String> p = new Pair<>(e.getString(Sjm.OWNERID), e.getString(Sjm.SYSMLID));
                        addEdges.add(p);
                    }

                    String doc = e.optString("documentation");
                    if (doc != null && !doc.equals("")) {
                        NodeUtil.processDocumentEdges(e.getString(Sjm.SYSMLID), doc,
                            viewEdges);
                    }

                    if (nodeType == DbNodeTypes.SITEANDPACKAGE.getValue()) {
                        createOrUpdateSiteChar(e, projectId, refId, services);
                    }

                    if (e.has("_contents")) {
                        JSONObject contents = e.optJSONObject("_contents");
                        if (contents != null) {
                            NodeUtil.processContentsJson(e.getString(Sjm.SYSMLID), contents, viewEdges);
                        }
                    } else if (e.has("specification") && nodeType == DbNodeTypes.INSTANCESPECIFICATION.getValue()) {
                        JSONObject iss = e.optJSONObject("specification");
                        if (iss != null) {
                            NodeUtil.processInstanceSpecificationSpecificationJson(e.getString(Sjm.SYSMLID), iss,
                                viewEdges);
                            NodeUtil.processContentsJson(e.getString(Sjm.SYSMLID), iss, viewEdges);
                        }
                    }
                    if (nodeType == DbNodeTypes.VIEW.getValue() || nodeType == DbNodeTypes.DOCUMENT.getValue()) {
                        JSONArray owned = e.optJSONArray(Sjm.OWNEDATTRIBUTEIDS);
                        if (owned != null) {
                            for (int j = 0; j < owned.length(); j++) {
                                Pair<String, String> p = new Pair<>(e.getString(Sjm.SYSMLID), owned.getString(j));
                                childViewEdges.add(p);
                            }
                        }
                    }
                    if (isPartProperty(e)) {
                        String type = e.optString(Sjm.TYPEID);
                        if (type != null) {
                            Pair<String, String> p = new Pair<>(e.getString(Sjm.SYSMLID), type);
                            childViewEdges.add(p);
                        }
                    }
                }

                for (int i = 0; i < deleted.length(); i++) {
                    JSONObject e = deleted.getJSONObject(i);
                    jmsDeleted.put(e.getString(Sjm.SYSMLID));
                    pgh.deleteEdgesForNode(e.getString(Sjm.SYSMLID));
                    pgh.deleteNode(e.getString(Sjm.SYSMLID));
                    deletedSysmlIds.add(e.getString(Sjm.SYSMLID));
                }

                List<Pair<String, String>> updatedPlist = new LinkedList<>();
                for (int i = 0; i < updated.length(); i++) {
                    JSONObject e = updated.getJSONObject(i);
                    jmsUpdated.put(e.getString(Sjm.SYSMLID));
                    int nodeType = getNodeType(e).getValue();
                    pgh.deleteEdgesForNode(e.getString(Sjm.SYSMLID), true, DbEdgeTypes.CONTAINMENT);
                    pgh.deleteEdgesForNode(e.getString(Sjm.SYSMLID), false, DbEdgeTypes.VIEW);
                    pgh.deleteEdgesForNode(e.getString(Sjm.SYSMLID), false, DbEdgeTypes.CHILDVIEW);

                    if (e.has(Sjm.OWNERID) && e.getString(Sjm.OWNERID) != null && e.getString(Sjm.SYSMLID) != null) {
                        Pair<String, String> p = new Pair<>(e.getString(Sjm.OWNERID), e.getString(Sjm.SYSMLID));
                        addEdges.add(p);
                    }
                    String doc = e.optString("documentation");
                    if (doc != null && !doc.equals("")) {
                        NodeUtil.processDocumentEdges(e.getString(Sjm.SYSMLID), doc, viewEdges);
                    }

                    if (nodeType == DbNodeTypes.SITEANDPACKAGE.getValue()) {
                        createOrUpdateSiteChar(e, projectId, refId, services);
                    }

                    if (e.has("_contents")) {
                        JSONObject contents = e.optJSONObject("_contents");
                        if (contents != null) {
                            NodeUtil.processContentsJson(e.getString(Sjm.SYSMLID), contents, viewEdges);
                        }
                    } else if (e.has("specification") && nodeType == DbNodeTypes.INSTANCESPECIFICATION.getValue()) {
                        JSONObject iss = e.optJSONObject("specification");
                        if (iss != null) {
                            NodeUtil.processInstanceSpecificationSpecificationJson(e.getString(Sjm.SYSMLID), iss,
                                viewEdges);
                            NodeUtil.processContentsJson(e.getString(Sjm.SYSMLID), iss, viewEdges); //for sections
                        }
                    }
                    if (nodeType == DbNodeTypes.VIEW.getValue() || nodeType == DbNodeTypes.DOCUMENT.getValue()) {
                        JSONArray owned = e.optJSONArray(Sjm.OWNEDATTRIBUTEIDS);
                        if (owned != null) {
                            for (int j = 0; j < owned.length(); j++) {
                                Pair<String, String> p = new Pair<>(e.getString(Sjm.SYSMLID), owned.getString(j));
                                childViewEdges.add(p);
                            }
                        }
                    }
                    if (isPartProperty(e)) {
                        String type = e.optString(Sjm.TYPEID);
                        if (type != null) {
                            Pair<String, String> p = new Pair<>(e.getString(Sjm.SYSMLID), type);
                            childViewEdges.add(p);
                        }
                    }
                    if (e.has(Sjm.ELASTICID)) {
                        Map<String, String> updatedNode = new HashMap<>();
                        updatedNode.put(Sjm.ELASTICID, e.getString(Sjm.ELASTICID));
                        updatedNode.put(Sjm.SYSMLID, e.getString(Sjm.SYSMLID));
                        updatedNode.put("nodetype", Integer.toString(getNodeType(e).getValue()));
                        updatedNode.put("deleted", "false");
                        updatedNode.put("lastcommit", commitElasticId);
                        nodeUpdates.add(updatedNode);
                    }
                }

                for (Pair<String, String> e : addEdges) {
                    if (!pgh.edgeExists(e.first, e.second, DbEdgeTypes.CONTAINMENT)) {
                        String edgeTest = e.first + e.second + DbEdgeTypes.CONTAINMENT.getValue();
                        if (!uniqueEdge.contains(edgeTest)) {
                            Map<String, String> edge = new HashMap<>();
                            edge.put("parent", e.first);
                            edge.put("child", e.second);
                            edge.put("edgetype", Integer.toString(DbEdgeTypes.CONTAINMENT.getValue()));
                            edgeInserts.add(edge);
                            uniqueEdge.add(edgeTest);
                        }
                    }
                }

                for (Pair<String, String> e : viewEdges) {
                    if (!pgh.edgeExists(e.first, e.second, DbEdgeTypes.VIEW)) {
                        String edgeTest = e.first + e.second + DbEdgeTypes.VIEW.getValue();
                        if (!uniqueEdge.contains(edgeTest)) {
                            Map<String, String> edge = new HashMap<>();
                            edge.put("parent", e.first);
                            edge.put("child", e.second);
                            edge.put("edgetype", Integer.toString(DbEdgeTypes.VIEW.getValue()));
                            childEdgeInserts.add(edge);
                            uniqueEdge.add(edgeTest);
                        }
                    }
                }
                for (Pair<String, String> e : childViewEdges) {
                    if (!pgh.edgeExists(e.first, e.second, DbEdgeTypes.CHILDVIEW)) {
                        String edgeTest = e.first + e.second + DbEdgeTypes.CHILDVIEW.getValue();
                        if (!uniqueEdge.contains(edgeTest)) {
                            Map<String, String> edge = new HashMap<>();
                            edge.put("parent", e.first);
                            edge.put("child", e.second);
                            edge.put("edgetype", Integer.toString(DbEdgeTypes.CHILDVIEW.getValue()));
                            childEdgeInserts.add(edge);
                            uniqueEdge.add(edgeTest);
                        }
                    }
                }

                Savepoint sp = null;
                try {//do node insert, updates, and containment edge updates
                    //do bulk delete edges for affected sysmlids here - delete containment, view and childview
                    sp = pgh.startTransaction();
                    pgh.runBulkQueries(nodeInserts, "nodes");
                    pgh.runBulkQueries(nodeUpdates, "updates");
                    pgh.updateBySysmlIds("nodes", "lastCommit", commitElasticId, deletedSysmlIds);
                    pgh.commitTransaction();
                    pgh.insertCommit(commitElasticId, DbCommitTypes.COMMIT, creator);
                    sp = pgh.startTransaction();
                    pgh.runBulkQueries(edgeInserts, "edges");
                    pgh.commitTransaction();
                    pgh.cleanEdges();
                    //TODO this should be part of transaction but will close the conn
                } catch (Exception e) {
                    try {
                        pgh.rollBackToSavepoint(sp);
                    } catch (SQLException se) {
                        logger.error(String.format("%s", LogUtil.getStackTrace(se)));
                    }
                    logger.error(String.format("%s", LogUtil.getStackTrace(e)));
                    return false;
                } finally {
                    pgh.close();
                }
                try {//view and childview edge updates
                    sp = pgh.startTransaction();
                    pgh.runBulkQueries(childEdgeInserts, "edges");
                    pgh.commitTransaction();
                    pgh.cleanEdges();
                } catch (Exception e) {
                    try {
                        pgh.rollBackToSavepoint(sp);
                    } catch (SQLException se) {
                        logger.error(String.format("%s", LogUtil.getStackTrace(se)));
                    }
                    logger.error(String.format("%s", LogUtil.getStackTrace(e))); //childedges are not critical
                } finally {
                    pgh.close();
                }
                try {
                    eh.indexElement(delta); //initial commit may fail to read back but does get indexed
                } catch (Exception e) {
                    logger.error(String.format("%s", LogUtil.getStackTrace(e)));
                }
                // TODO: Add in the Alfresco Site creation based on type=SiteAndPackage

            } catch (Exception e1) {
                logger.warn("Could not complete graph storage");
                e1.printStackTrace();
                return false;
            }
        } else {
            logger.error("Elasticsearch insert error occurred");
            return false;
        }

        jmsWorkspace.put("addedElements", jmsAdded);
        jmsWorkspace.put("updatedElements", jmsUpdated);
        jmsWorkspace.put("deletedElements", jmsDeleted);

        jmsPayload.put("refs", jmsWorkspace);

        return true;
    }

    /**
     * Send off the deltas to various endpoints
     *
     * @param deltaJson JSONObject of the deltas to be published
     * @param projectId String of the project Id to post to
     * @param source    Source of the delta (e.g., MD, EVM, whatever, only necessary for MD so it can
     *                  ignore)
     * @return true if publish completed
     * @throws JSONException
     */
    public static boolean sendDeltas(JSONObject deltaJson, String projectId, String workspaceId,
        String source, ServiceRegistry services, boolean withChildViews) throws JSONException {

        JSONObject jmsPayload = new JSONObject();
        try {
            eh = new ElasticHelper();
        } catch (Exception e) {
            logger.error(String.format("%s", LogUtil.getStackTrace(e)));
        }

        if (!processDeltasForDb(deltaJson, projectId, workspaceId, jmsPayload, withChildViews, services)) {
            return false;
        }

        if (source != null) {
            jmsPayload.put("source", source);
        }

        sendJmsMsg(jmsPayload, TYPE_DELTA, workspaceId, projectId);

        return true;
    }

    public static void sendOrganizationDelta(String orgId, String orgName, String user) {
        PostgresHelper pgh = new PostgresHelper();
        pgh.createOrganization(orgId, orgName);
    }

    public static void sendProjectDelta(JSONObject o, String orgId, String user) {

        PostgresHelper pgh = new PostgresHelper();
        String date = TimeUtils.toTimestamp(new Date().getTime());
        JSONObject jmsMsg = new JSONObject();
        JSONObject siteElement = new JSONObject();
        JSONObject site = new JSONObject();
        JSONObject siteHoldingBin = new JSONObject();
        JSONObject projectHoldingBin = new JSONObject();
        JSONObject viewInstanceBin = new JSONObject();
        JSONObject project = new JSONObject();
        ElasticResult eProject = null;
        ElasticResult eSite = null;
        ElasticResult eProjectHoldingBin = null;
        ElasticResult eViewInstanceBin = null;
        ElasticResult eSiteHoldingBin = null;
        String projectSysmlid = null;
        String projectName = null;
        String projectLocation = o.optString("location");

        if (o.has("name")) {
            projectName = o.getString("name");
        } else {
            projectName = orgId + "_no_project";
        }

        if (o.has(Sjm.SYSMLID)) {
            projectSysmlid = o.getString(Sjm.SYSMLID);
        } else {
            projectSysmlid = orgId + "_no_project";
        }

        pgh.createProjectDatabase(projectSysmlid, orgId, projectName, projectLocation);

        siteElement.put(Sjm.SYSMLID, orgId);

        project = createNode(projectSysmlid, user, date, o);
        site = createNode(orgId, user, date, siteElement);

        siteHoldingBin = createNode("holding_bin_" + orgId, user, date, null);
        siteHoldingBin.put(Sjm.NAME, "Holding Bin");
        siteHoldingBin.put(Sjm.OWNERID, orgId);
        siteHoldingBin.put(Sjm.TYPE, "Package");
        siteHoldingBin.put("project", "");
        siteHoldingBin.put(Sjm.URI, JSONObject.NULL);
        siteHoldingBin.put(Sjm.APPLIEDSTEREOTYPEIDS, new JSONArray());
        siteHoldingBin.put(Sjm.ISSITE, false);
        siteHoldingBin.put(Sjm.APPLIEDSTEREOTYPEINSTANCEID, JSONObject.NULL);
        siteHoldingBin.put(Sjm.CLIENTDEPENDENCYIDS, new JSONArray());
        siteHoldingBin.put(Sjm.DOCUMENTATION, JSONObject.NULL);
        siteHoldingBin.put(Sjm.ELEMENTIMPORTIDS, new JSONArray());
        siteHoldingBin.put(Sjm.MDEXTENSIONSIDS, new JSONArray());
        siteHoldingBin.put(Sjm.NAMEEXPRESSION, JSONObject.NULL);
        siteHoldingBin.put(Sjm.PACKAGEIMPORTIDS, new JSONArray());
        siteHoldingBin.put(Sjm.PACKAGEMERGEIDS, new JSONArray());
        siteHoldingBin.put(Sjm.PROFILEAPPLICATIONIDS, new JSONArray());
        siteHoldingBin.put(Sjm.SUPPLIERDEPENDENCYIDS, new JSONArray());
        siteHoldingBin.put(Sjm.SYNCELEMENTID, JSONObject.NULL);
        siteHoldingBin.put(Sjm.TEMPLATEBINDINGIDS, new JSONArray());
        siteHoldingBin.put(Sjm.TEMPLATEPARAMETERID, JSONObject.NULL);
        siteHoldingBin.put(Sjm.VISIBILITY, "public");

        projectHoldingBin = createNode("holding_bin_" + projectSysmlid, user, date, null);
        projectHoldingBin.put(Sjm.NAME, "Holding Bin");
        projectHoldingBin.put(Sjm.OWNERID, projectSysmlid);
        projectHoldingBin.put(Sjm.TYPE, "Package");
        projectHoldingBin.put(Sjm.URI, JSONObject.NULL);
        projectHoldingBin.put(Sjm.APPLIEDSTEREOTYPEIDS, new JSONArray());
        projectHoldingBin.put(Sjm.ISSITE, false);
        projectHoldingBin.put(Sjm.APPLIEDSTEREOTYPEINSTANCEID, JSONObject.NULL);
        projectHoldingBin.put(Sjm.CLIENTDEPENDENCYIDS, new JSONArray());
        projectHoldingBin.put(Sjm.DOCUMENTATION, JSONObject.NULL);
        projectHoldingBin.put(Sjm.ELEMENTIMPORTIDS, new JSONArray());
        projectHoldingBin.put(Sjm.MDEXTENSIONSIDS, new JSONArray());
        projectHoldingBin.put(Sjm.NAMEEXPRESSION, JSONObject.NULL);
        projectHoldingBin.put(Sjm.PACKAGEIMPORTIDS, new JSONArray());
        projectHoldingBin.put(Sjm.PACKAGEMERGEIDS, new JSONArray());
        projectHoldingBin.put(Sjm.PROFILEAPPLICATIONIDS, new JSONArray());
        projectHoldingBin.put(Sjm.SUPPLIERDEPENDENCYIDS, new JSONArray());
        projectHoldingBin.put(Sjm.SYNCELEMENTID, JSONObject.NULL);
        projectHoldingBin.put(Sjm.TEMPLATEBINDINGIDS, new JSONArray());
        projectHoldingBin.put(Sjm.TEMPLATEPARAMETERID, JSONObject.NULL);
        projectHoldingBin.put(Sjm.VISIBILITY, "public");

        viewInstanceBin = createNode("view_instances_bin_" + projectSysmlid, user, date, null);
        viewInstanceBin.put(Sjm.NAME, "View Instances Bin");
        viewInstanceBin.put(Sjm.OWNERID, projectSysmlid);
        viewInstanceBin.put(Sjm.TYPE, "Package");
        viewInstanceBin.put(Sjm.URI, JSONObject.NULL);
        viewInstanceBin.put(Sjm.APPLIEDSTEREOTYPEIDS, new JSONArray());
        viewInstanceBin.put(Sjm.ISSITE, false);
        viewInstanceBin.put(Sjm.APPLIEDSTEREOTYPEINSTANCEID, JSONObject.NULL);
        viewInstanceBin.put(Sjm.CLIENTDEPENDENCYIDS, new JSONArray());
        viewInstanceBin.put(Sjm.DOCUMENTATION, JSONObject.NULL);
        viewInstanceBin.put(Sjm.ELEMENTIMPORTIDS, new JSONArray());
        viewInstanceBin.put(Sjm.MDEXTENSIONSIDS, new JSONArray());
        viewInstanceBin.put(Sjm.NAMEEXPRESSION, JSONObject.NULL);
        viewInstanceBin.put(Sjm.PACKAGEIMPORTIDS, new JSONArray());
        viewInstanceBin.put(Sjm.PACKAGEMERGEIDS, new JSONArray());
        viewInstanceBin.put(Sjm.PROFILEAPPLICATIONIDS, new JSONArray());
        viewInstanceBin.put(Sjm.SUPPLIERDEPENDENCYIDS, new JSONArray());
        viewInstanceBin.put(Sjm.SYNCELEMENTID, JSONObject.NULL);
        viewInstanceBin.put(Sjm.TEMPLATEBINDINGIDS, new JSONArray());
        viewInstanceBin.put(Sjm.TEMPLATEPARAMETERID, JSONObject.NULL);
        viewInstanceBin.put(Sjm.VISIBILITY, "public");

        try {
            ElasticHelper eh = new ElasticHelper();

            // only insert if the site does not exist already
            if (pgh.getNodeFromSysmlId(orgId) == null) {

                eSite = eh.indexElement(site);
                eSiteHoldingBin = eh.indexElement(siteHoldingBin);

                pgh.insertNode(eSite.elasticId, orgId, DbNodeTypes.SITE);
                pgh.insertNode(eSiteHoldingBin.elasticId, "holding_bin_" + orgId, DbNodeTypes.HOLDINGBIN);
                pgh.insertEdge(orgId, eSiteHoldingBin.sysmlid, DbEdgeTypes.CONTAINMENT);
            }

            // only insert if the project does not exist already
            if (pgh.getNodeFromSysmlId(projectSysmlid) == null) {

                eProject = eh.indexElement(project);
                eProjectHoldingBin = eh.indexElement(projectHoldingBin);
                eViewInstanceBin = eh.indexElement(viewInstanceBin);

                pgh.insertNode(eProject.elasticId, eProject.sysmlid, DbNodeTypes.PROJECT);
                pgh.insertNode(eProjectHoldingBin.elasticId, "holding_bin_" + projectSysmlid, DbNodeTypes.HOLDINGBIN);
                pgh.insertNode(eViewInstanceBin.elasticId, "view_instances_bin_" + projectSysmlid, DbNodeTypes.HOLDINGBIN);

                pgh.insertEdge(orgId, eProject.sysmlid, DbEdgeTypes.CONTAINMENT);
                pgh.insertEdge(projectSysmlid, eProjectHoldingBin.sysmlid, DbEdgeTypes.CONTAINMENT);
                pgh.insertEdge(projectSysmlid, eViewInstanceBin.sysmlid, DbEdgeTypes.CONTAINMENT);

                JSONObject addedElements = new JSONObject();
                JSONArray elementsArray = new JSONArray();
                elementsArray.put(projectHoldingBin);
                elementsArray.put(viewInstanceBin);
                addedElements.put("addedElements", elementsArray);
                jmsMsg.put("refs", addedElements);
                jmsMsg.put("source", "mms");
                sendJmsMsg(jmsMsg, TYPE_DELTA, null, projectSysmlid);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // make sure only one branch is made at a time
    public static synchronized JSONObject sendBranch(String projectId, JSONObject src, JSONObject created, String elasticId, Boolean isTag, String source) throws JSONException {
        // FIXME: need to include branch in commit history
        JSONObject branchJson = new JSONObject();
        branchJson.put("source", source);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.submit(() -> {
            String srcId = src.optString(Sjm.SYSMLID);

            PostgresHelper pgh = new PostgresHelper(srcId);
            pgh.setProject(projectId);

            try {
                pgh.createBranchFromWorkspace(created.optString(Sjm.SYSMLID), created.optString(Sjm.NAME), elasticId,
                    isTag);
                pgh.setWorkspace(created.getString(Sjm.SYSMLID));
                eh = new ElasticHelper();
                Set<String> elementsToUpdate = pgh.getElasticIds();
                String payload = new JSONObject().put("script", new JSONObject().put("inline",
                    "if(ctx._source.containsKey(\"" + Sjm.INREFIDS + "\")){ctx._source." + Sjm.INREFIDS
                        + ".add(params.refId)} else {ctx._source." + Sjm.INREFIDS + " = [params.refId]}")
                    .put("params", new JSONObject().put("refId", created.getString(Sjm.SYSMLID)))).toString();
                eh.bulkUpdateElements(elementsToUpdate, payload);
                created.put("status", "created");

            } catch (Exception e) {
                // TODO Auto-generated catch block
                created.put("status", "failed");
                e.printStackTrace();
            }

            try {
                eh.updateElement(elasticId, new JSONObject().put("doc", created));
            } catch (Exception e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }

            branchJson.put("createdRef", created);
            sendJmsMsg(branchJson, TYPE_BRANCH, src.optString(Sjm.SYSMLID), projectId);
        });
        executor.shutdown();

        return branchJson;
    }

    public static JSONObject getWorkspaceDetails(EmsScriptNode ws, Date date) {
        JSONObject json = new JSONObject();
        addWorkspaceNamesAndIds(json, ws, false);
        if (null == date) {
            date = new Date();
        }
        json.put("time", TimeUtils.toTimestamp(date));
        return json;
    }

    /**
     * Add the workspace name and id metadata onto the provided JSONObject
     *
     * @param json
     * @param ws
     * @throws JSONException
     */
    public static void addWorkspaceNamesAndIds(JSONObject json, EmsScriptNode ws, boolean chkPermissions)
        throws JSONException {
        json.put("name", ws.getProperty("ems:workspace_name"));
        json.put("id", ws.getNodeRef().getId());
        json.put("qualifiedName", EmsScriptNode.getQualifiedName(ws, null));
        json.put("qualifiedId", EmsScriptNode.getQualifiedId(ws, null));

        // If it is the master workspace, then determine if the user has permissions,
        // and add a indication to the json:
        if (ws == null && chkPermissions) {
            // Decided not to do this using the site manger, but rather with the ldap group
            // checkSiteManagerPermissions(json, services);
            json.put("workspaceOperationsPermission", NodeUtil.userHasWorkspaceLdapPermissions());
        }
    }

    public static boolean sendMerge(EmsScriptNode src, EmsScriptNode dst, Date srcDateTime) throws JSONException {
        // FIXME: need to add merge event into commit history
        JSONObject mergeJson = new JSONObject();

        mergeJson.put("sourceWorkspace", getWorkspaceDetails(src, srcDateTime));
        mergeJson.put("mergedWorkspace", getWorkspaceDetails(dst, null));

        return sendJmsMsg(mergeJson, TYPE_MERGE, null, null);
    }

    protected static boolean sendJmsMsg(JSONObject json, String eventType, String refId, String projectId) {
        boolean status = false;
        if (jmsConnection != null) {
            status = jmsConnection.publish(json, eventType, refId, projectId);
            if (logger.isDebugEnabled()) {
                String msg =
                    "Event: " + eventType + ", RefId: " + refId + ", ProjectId: " + projectId + "\n";
                msg += "JSONObject: " + json;
                logger.debug(msg);
            }
        } else {
            if (logger.isInfoEnabled())
                logger.info("JMS Connection not available");
        }

        return status;
    }

    /**
     * Send off progress to various endpoints
     *
     * @param msg       String message to be published
     * @param projectId String of the project Id to post to
     * @return true if publish completed
     * @throws JSONException
     */
    public static boolean sendProgress(String msg, String workspaceId, String projectId) {
        // FIXME: temporarily remove progress notifications until it's actually
        // ready to be used
        // boolean jmsStatus = false;
        //
        // if (jmsConnection != null) {
        // jmsConnection.setWorkspace( workspaceId );
        // jmsConnection.setProjectId( projectId );
        // jmsStatus = jmsConnection.publishTopic( msg, "progress" );
        // }
        //
        // return jmsStatus;
        return true;
    }


    private static JSONObject createNode(String sysmlid, String user, String date, JSONObject e) {
        // JSONObject o = new JSONObject();

        if (e == null) {
            e = new JSONObject();
        }

        e.put(Sjm.SYSMLID, sysmlid);
        e.put(Sjm.CREATOR, user);
        e.put(Sjm.CREATED, date);
        e.put(Sjm.MODIFIER, user);
        e.put(Sjm.MODIFIED, date);

        return e;
    }

    public static boolean createOrUpdateSiteChar(JSONObject siteChar, String projectId, String refId, ServiceRegistry services) {

        EmsNodeUtil emsNodeUtil = new EmsNodeUtil(projectId, refId);
        String folderName = siteChar.optString(Sjm.NAME);
        String folderId = siteChar.optString(Sjm.SYSMLID);

        if (services != null && folderName != null && folderId != null) {
            String orgId = emsNodeUtil.getOrganizationFromProject(projectId);
            SiteInfo orgInfo = services.getSiteService().getSite(orgId);
            if (orgInfo != null) {

                EmsScriptNode site = new EmsScriptNode(orgInfo.getNodeRef(), services);

                if (!site.checkPermissions(PermissionService.WRITE)) {
                    return false;
                }

                EmsScriptNode documentLibrary = site.childByNamePath("documentLibrary", false, null, true);
                if (documentLibrary == null) {
                    documentLibrary = site.createFolder("documentLibrary");
                    documentLibrary.createOrUpdateProperty(Acm.CM_TITLE, "Document Library");
                }
                EmsScriptNode projectDocumentLibrary = documentLibrary.childByNamePath(projectId, false, null, true);
                if (projectDocumentLibrary == null) {
                    projectDocumentLibrary = documentLibrary.createFolder(projectId);
                }
                EmsScriptNode siteCharFolder = projectDocumentLibrary.childByNamePath(folderId, false, null, true);
                if (siteCharFolder == null) {
                    siteCharFolder = projectDocumentLibrary.createFolder(folderId);
                    siteCharFolder.createOrUpdateProperty(Acm.CM_TITLE, folderName);
                    return true;
                } else {
                    siteCharFolder.createOrUpdateProperty(Acm.CM_TITLE, folderName);
                    return true;
                }
            }
        }

        return false;
    }

}
