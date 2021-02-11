package org.cbioportal.web;

import java.io.IOException;
import java.util.*;

import javax.servlet.http.HttpServletResponse;
import javax.validation.constraints.Size;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.cbioportal.web.parameter.CustomDataSession;
import org.cbioportal.web.parameter.PageSettings;
import org.cbioportal.web.parameter.PageSettingsData;
import org.cbioportal.web.parameter.PageSettingsIdentifier;
import org.cbioportal.web.parameter.PagingConstants;
import org.cbioportal.web.parameter.StudyPageSettings;
import org.cbioportal.web.parameter.VirtualStudy;
import org.cbioportal.web.parameter.VirtualStudyData;
import org.cbioportal.web.util.SessionServiceRequestHandler;
import org.cbioportal.session_service.domain.Session;
import org.cbioportal.session_service.domain.SessionType;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

@Controller
@RequestMapping("/session")
public class SessionServiceController {

    private static final Log LOG = LogFactory.getLog(SessionServiceController.class);
    
    @Autowired
    SessionServiceRequestHandler sessionServiceRequestHandler;

    @Value("${session.service.url:}")
    private String sessionServiceURL;

    private Boolean isSessionServiceEnabled() {
        return !StringUtils.isEmpty(sessionServiceURL);
    }

    private boolean isAuthorized() {

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return !(authentication == null || (authentication instanceof AnonymousAuthenticationToken));
    }

    private String userName() {

        return ((UserDetails) SecurityContextHolder.getContext().getAuthentication().getPrincipal()).getUsername();
    }

    private boolean sameOrigin(Set<String> set1, Set<String> set2) {

        if (set1 == null || set2 == null) {
            return false;
        }
        if (set1.size() != set2.size()) {
            return false;
        }
        return set1.containsAll(set2);
    }

    private PageSettings getRecentlyUpdatePageSettings(String query) {

        RestTemplate restTemplate = new RestTemplate();

        HttpEntity<String> httpEntity = new HttpEntity<String>(query, sessionServiceRequestHandler.getHttpHeaders());

        ResponseEntity<List<PageSettings>> responseEntity = restTemplate.exchange(
                sessionServiceURL + SessionType.settings + "/query/fetch",
                HttpMethod.POST,
                httpEntity,
                new ParameterizedTypeReference<List<PageSettings>>() {});

        List<PageSettings> sessions = responseEntity.getBody();

        // sort last updated in descending order
        sessions.sort((PageSettings s1, PageSettings s2) -> {
            return ((StudyPageSettings) s1.getData()).getLastUpdated() > ((StudyPageSettings) s2.getData())
                    .getLastUpdated() ? -1 : 1;
        });

        return sessions.isEmpty() ? null : sessions.get(0);
    }

    private ResponseEntity<Session> addSession(SessionType type, Optional<SessionOperation> operation,
            JSONObject body) {
        try {
            HttpEntity httpEntity = null;
            ObjectMapper mapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                    false);
            if (type.equals(SessionType.virtual_study) || type.equals(SessionType.group)) {
                // JSON from file to Object
                VirtualStudyData virtualStudyData = mapper.readValue(body.toString(), VirtualStudyData.class);

                if (isAuthorized()) {
                    virtualStudyData.setOwner(userName());
                    if ((operation.isPresent() && operation.get().equals(SessionOperation.save))
                            || type.equals(SessionType.group)) {
                        virtualStudyData.setUsers(Collections.singleton(userName()));
                    }
                }

                // use basic authentication for session service if set
                httpEntity = new HttpEntity<VirtualStudyData>(virtualStudyData, sessionServiceRequestHandler.getHttpHeaders());
            } else if (type.equals(SessionType.settings)) {
                if (!(isAuthorized())) {
                    return new ResponseEntity<>(HttpStatus.SERVICE_UNAVAILABLE);
                }
                StudyPageSettings studyPageSettings = mapper.readValue(body.toString(), StudyPageSettings.class);
                studyPageSettings.setOwner(userName());
                httpEntity = new HttpEntity<StudyPageSettings>(studyPageSettings, sessionServiceRequestHandler.getHttpHeaders());

            } else if(type.equals(SessionType.custom_data)) {
                // JSON from file to Object
                CustomAttributeWithData customData = mapper.readValue(body.toString(), CustomAttributeWithData.class);

                if (isAuthorized()) {
                    customData.setOwner(userName());
                    customData.setUsers(Collections.singleton(userName()));
                }

                // use basic authentication for session service if set
                httpEntity = new HttpEntity<CustomAttributeWithData>(customData, sessionServiceRequestHandler.getHttpHeaders());
            } else {
                httpEntity = new HttpEntity<JSONObject>(body, sessionServiceRequestHandler.getHttpHeaders());
            }
            // returns {"id":"5799648eef86c0e807a2e965"}
            // using HashMap because converter is MappingJackson2HttpMessageConverter
            // (Jackson 2 is on classpath)
            // was String when default converter StringHttpMessageConverter was used
            RestTemplate restTemplate = new RestTemplate();
            ResponseEntity<Session> resp = restTemplate.exchange(sessionServiceURL + type, HttpMethod.POST, httpEntity,
                    Session.class);

            return new ResponseEntity<>(resp.getBody(), resp.getStatusCode());

        } catch (IOException e) {
            LOG.error(e);
            return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
        }
    }

    @RequestMapping(value = "/{type}/{id}", method = RequestMethod.GET)
    public ResponseEntity<Session> getSession(@PathVariable SessionType type, @PathVariable String id,
            HttpServletResponse response) {

        try {
            Session session = sessionServiceRequestHandler.getSession(type, id);
            return new ResponseEntity<>(session, HttpStatus.OK);

        } catch (Exception exception) {
            LOG.error(exception);
            return new ResponseEntity<Session>(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @RequestMapping(value = "/virtual_study", method = RequestMethod.GET)
    public ResponseEntity<List<VirtualStudy>> getUserStudies() throws JsonProcessingException {

        if (isSessionServiceEnabled() && isAuthorized()) {
            try {

                Map<String, String> map = new HashMap<>();
                map.put("data.users", userName());

                ObjectMapper mapper = new ObjectMapper();
                String query = mapper.writeValueAsString(map);

                RestTemplate restTemplate = new RestTemplate();

                HttpEntity<Object> httpEntity = new HttpEntity<Object>(query, sessionServiceRequestHandler.getHttpHeaders());

                ResponseEntity<List<VirtualStudy>> responseEntity = restTemplate.exchange(
                        sessionServiceURL + "virtual_study/query?field=data.users&value=" + userName(),
                        HttpMethod.GET,
                        httpEntity,
                        new ParameterizedTypeReference<List<VirtualStudy>>() {});

                return new ResponseEntity<>(responseEntity.getBody(), HttpStatus.OK);
            } catch (Exception exception) {
                LOG.error(exception);
                return new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR);
            }
        }
        return new ResponseEntity<>(HttpStatus.UNAUTHORIZED);
    }

    @RequestMapping(value = "/{type}", method = RequestMethod.POST)
    public ResponseEntity<Session> addSession(@PathVariable SessionType type, @RequestBody JSONObject body)
            throws IOException {

        return addSession(type, Optional.empty(), body);
    }

    @RequestMapping(value = "/virtual_study/save", method = RequestMethod.POST)
    public ResponseEntity<Session> addUserSavedVirtualStudy(@RequestBody JSONObject body) throws IOException {

        return addSession(SessionType.virtual_study, Optional.of(SessionOperation.save), body);
    }

    @RequestMapping(value = "/{type:virtual_study|group|custom_data}/{operation}/{id}", method = RequestMethod.GET)
    public void updateUsersInVirtualStudy(@PathVariable SessionType type, @PathVariable String id,
            @PathVariable Operation operation, HttpServletResponse response) throws IOException {

        if (isSessionServiceEnabled() && isAuthorized()) {
            ObjectMapper mapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                    false);
            Set<String> users = new HashSet<>();
            HttpEntity httpEntity;
            if (type.equals(SessionType.custom_data)) {
                String virtualStudyStr = mapper.writeValueAsString(getSession(type, id, response).getBody());
                CustomDataSession customDataSession = mapper.readValue(virtualStudyStr, CustomDataSession.class);
                CustomAttributeWithData customAttributeWithData = customDataSession.getData();
                users = customAttributeWithData.getUsers();
                updateUserList(operation, users);
                customAttributeWithData.setUsers(users);
                httpEntity = new HttpEntity<>(customAttributeWithData, sessionServiceRequestHandler.getHttpHeaders());

            } else {
                String virtualStudyStr = mapper.writeValueAsString(getSession(type, id, response).getBody());
                VirtualStudy virtualStudy = mapper.readValue(virtualStudyStr, VirtualStudy.class);
                VirtualStudyData virtualStudyData = virtualStudy.getData();
                users = virtualStudyData.getUsers();
                updateUserList(operation, users);
                virtualStudyData.setUsers(users);
                httpEntity = new HttpEntity<>(virtualStudyData, sessionServiceRequestHandler.getHttpHeaders());
            }

            RestTemplate restTemplate = new RestTemplate();
            restTemplate.put(sessionServiceURL + type + "/" + id, httpEntity);

            response.sendError(HttpStatus.OK.value());
        } else {
            response.sendError(HttpStatus.SERVICE_UNAVAILABLE.value());
        }

    }

    private void updateUserList(Operation operation, Set<String> users) {
        switch (operation) {
        case add: {
            users.add(userName());
            break;
        }
        case delete: {
            users.remove(userName());
            break;
        }
        }
    }

    @RequestMapping(value = "/groups/fetch", method = RequestMethod.POST)
    public ResponseEntity<List<VirtualStudy>> fetchUserGroups(
            @Size(min = 1, max = PagingConstants.MAX_PAGE_SIZE) @RequestBody List<String> studyIds,
            HttpServletResponse response) throws IOException {

        if (isSessionServiceEnabled() && isAuthorized()) {

            String username = userName();
            Map<String, Object> map = new HashMap<>();
            map.put("data.owner", username);
            map.put("data.origin", studyIds);

            ObjectMapper mapper = new ObjectMapper();

            // ignore origin studies order
            // add $size to make sure origin studies is not a subset
            String query = "{ $and: [{ \"data.users\": \"" + username + "\" }, { \"data.origin\": { $all: "
                    + mapper.writeValueAsString(studyIds) + " } }, { \"data.origin\": { $size: " + studyIds.size()
                    + " } } ]}";

            RestTemplate restTemplate = new RestTemplate();

            HttpEntity<String> httpEntity = new HttpEntity<String>(query, sessionServiceRequestHandler.getHttpHeaders());

            ResponseEntity<List<VirtualStudy>> responseEntity = restTemplate.exchange(
                    sessionServiceURL + SessionType.group + "/query/fetch",
                    HttpMethod.POST,
                    httpEntity,
                    new ParameterizedTypeReference<List<VirtualStudy>>() {});

            return new ResponseEntity<>(responseEntity.getBody(), HttpStatus.OK);

        }
        return new ResponseEntity<>(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @RequestMapping(value = "/settings", method = RequestMethod.POST)
    public void updateUserPageSettings(@RequestBody PageSettingsData settingsData, HttpServletResponse response)
            throws IOException {

        try {
            ObjectMapper objectMapper = new ObjectMapper()
                    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                    .setSerializationInclusion(Include.NON_NULL);
            if (isSessionServiceEnabled() && isAuthorized()) {
                Map<String, Object> map = new HashMap<>();
                map.put("data.owner", userName());
                map.put("data.page", settingsData.getPage());
                map.put("data.origin", settingsData.getOrigin());

                String query = objectMapper.writeValueAsString(map);

                PageSettings pageSettings = getRecentlyUpdatePageSettings(query);

                JSONParser parser = new JSONParser();
                JSONObject jsonObject = (JSONObject) parser.parse(objectMapper.writeValueAsString(settingsData));

                if (pageSettings == null) {
                    addSession(SessionType.settings, null, jsonObject);
                } else {
                    updateSession(SessionType.settings, pageSettings.getId(), jsonObject, response);
                }
                response.setStatus(HttpStatus.OK.value());
            } else {
                response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
            }
        } catch (IOException | ParseException e) {
            LOG.error(e);
            response.setStatus(HttpStatus.BAD_REQUEST.value());
        }
    }

    // updates only allowed for type group and settings
    @RequestMapping(value = "/{type:group|settings}/{id}", method = RequestMethod.POST)
    public void updateSession(@PathVariable SessionType type, @PathVariable String id, @RequestBody JSONObject body,
            HttpServletResponse response) throws IOException {

        if (isAuthorized()) {

            ObjectMapper mapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                    false);

            Session session = getSession(type, id, response).getBody();

            if (session.getType().equals(SessionType.group)) {

                VirtualStudy virtualStudy = mapper.readValue(mapper.writeValueAsString(session), VirtualStudy.class);

                VirtualStudyData virtualStudyData = virtualStudy.getData();
                // only allow owner to update virtual study
                if (userName().equals(virtualStudyData.getOwner()) && virtualStudy.getType().equals(type)) {

                    VirtualStudyData updatedVirtualStudyData = mapper.readValue(body.toString(),
                            VirtualStudyData.class);
                    updatedVirtualStudyData.setCreated(virtualStudyData.getCreated());
                    updatedVirtualStudyData.setOwner(virtualStudyData.getOwner());
                    updatedVirtualStudyData.setUsers(virtualStudyData.getUsers());

                    virtualStudy.setData(mapper.writeValueAsString(updatedVirtualStudyData));
                    RestTemplate restTemplate = new RestTemplate();
                    HttpEntity<Object> httpEntity = new HttpEntity<Object>(updatedVirtualStudyData, sessionServiceRequestHandler.getHttpHeaders());

                    restTemplate.put(sessionServiceURL + type + "/" + id, httpEntity);
                    response.setStatus(HttpStatus.OK.value());
                } else {

                    response.setStatus(HttpStatus.UNAUTHORIZED.value());
                }
            } else {

                PageSettings pageSettings = mapper.readValue(mapper.writeValueAsString(session), PageSettings.class);
                PageSettingsData pageSettingsData = pageSettings.getData();
                StudyPageSettings updatedPageSettings = mapper.readValue(body.toString(), StudyPageSettings.class);
                // only allow owner to update his session and see if the origin(studies) are
                // same
                if (userName().equals(pageSettingsData.getOwner()) && session.getType().equals(type)
                        && sameOrigin(pageSettingsData.getOrigin(), updatedPageSettings.getOrigin())) {

                    updatedPageSettings.setCreated(pageSettingsData.getCreated());
                    updatedPageSettings.setOwner(pageSettingsData.getOwner());
                    updatedPageSettings.setOrigin(pageSettingsData.getOrigin());

                    RestTemplate restTemplate = new RestTemplate();
                    HttpEntity<Object> httpEntity = new HttpEntity<Object>(updatedPageSettings, sessionServiceRequestHandler.getHttpHeaders());

                    restTemplate.put(sessionServiceURL + type + "/" + id, httpEntity);
                    response.setStatus(HttpStatus.OK.value());
                } else {
                    response.setStatus(HttpStatus.UNAUTHORIZED.value());
                }
            }
        } else {
            response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
        }
    }

    @RequestMapping(value = "/settings/fetch", method = RequestMethod.POST)
    public ResponseEntity<PageSettingsData> getPageSettings(@RequestBody PageSettingsIdentifier pageSettingsIdentifier,
            HttpServletResponse response) {

        try {
            if (isSessionServiceEnabled() && isAuthorized()) {

                Map<String, Object> map = new HashMap<>();
                map.put("data.owner", userName());
                map.put("data.page", pageSettingsIdentifier.getPage());
                map.put("data.origin", pageSettingsIdentifier.getOrigin());

                ObjectMapper mapper = new ObjectMapper();
                String query = mapper.writeValueAsString(map);

                PageSettings pageSettings = getRecentlyUpdatePageSettings(query);

                return new ResponseEntity<>(pageSettings == null ? null : pageSettings.getData(), HttpStatus.OK);
            }
            return new ResponseEntity<>(HttpStatus.SERVICE_UNAVAILABLE);
        } catch (IOException e) {
            LOG.error(e);
            return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
        }
    }

    @RequestMapping(value = "/custom_data/fetch", method = RequestMethod.POST)
    public ResponseEntity<List<CustomDataSession>> fetchCustomProperties(
            @Size(min = 1, max = PagingConstants.MAX_PAGE_SIZE) @RequestBody List<String> studyIds,
            HttpServletResponse response) throws IOException {

        if (isSessionServiceEnabled() && isAuthorized()) {

            String username = userName();

            ObjectMapper mapper = new ObjectMapper();

            // ignore origin studies order
            // add $size to make sure origin studies is not a subset
            String query = "{ $and: [{ \"data.users\": \"" + username + "\" }, { \"data.origin\": { $all: "
                    + mapper.writeValueAsString(studyIds) + " } }, { \"data.origin\": { $size: " + studyIds.size()
                    + " } } ]}";

            RestTemplate restTemplate = new RestTemplate();

            HttpEntity<String> httpEntity = new HttpEntity<String>(query, sessionServiceRequestHandler.getHttpHeaders());

            ResponseEntity<List<CustomDataSession>> responseEntity = restTemplate.exchange(
                    sessionServiceURL + SessionType.custom_data + "/query/fetch",
                    HttpMethod.POST,
                    httpEntity,
                    new ParameterizedTypeReference<List<CustomDataSession>>() {});

            return new ResponseEntity<>(responseEntity.getBody(), HttpStatus.OK);

        }
        return new ResponseEntity<>(HttpStatus.SERVICE_UNAVAILABLE);
    }

}

enum Operation {
    add, delete;
}

enum SessionOperation {
    save, share;
}
